/*
 * dnssecjava - a DNSSEC validating stub resolver for Java
 * Copyright (c) 2013-2015 Ingo Bauersachs
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * This file is based on work under the following copyright and permission
 * notice:
 *
 *     Copyright (c) 2005 VeriSign. All rights reserved.
 *
 *     Redistribution and use in source and binary forms, with or without
 *     modification, are permitted provided that the following conditions are
 *     met:
 *
 *     1. Redistributions of source code must retain the above copyright
 *        notice, this list of conditions and the following disclaimer.
 *     2. Redistributions in binary form must reproduce the above copyright
 *        notice, this list of conditions and the following disclaimer in the
 *        documentation and/or other materials provided with the distribution.
 *     3. The name of the author may not be used to endorse or promote
 *        products derived from this software without specific prior written
 *        permission.
 *
 *     THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *     IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *     WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 *     ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
 *     INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *     (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *     SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 *     HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 *     STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 *     IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *     POSSIBILITY OF SUCH DAMAGE.
 */

package org.jitsi.dnssec.validator;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.jitsi.dnssec.R;
import org.jitsi.dnssec.SMessage;
import org.jitsi.dnssec.SRRset;
import org.jitsi.dnssec.SecurityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSSEC.Algorithm;
import org.xbill.DNS.DSRecord;
import org.xbill.DNS.DSRecord.Digest;
import org.xbill.DNS.Message;
import org.xbill.DNS.NSECRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.NameTooLongException;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

/**
 * This is a collection of routines encompassing the logic of validating different message types.
 *
 * @author davidb
 */
public class ValUtils {
  public static final String DIGEST_PREFERENCE = "org.jitsi.dnssec.digest_preference";
  public static final String DIGEST_HARDEN_DOWNGRADE = "org.jitsi.dnssec.harden_algo_downgrade";

  private static final Logger logger = LoggerFactory.getLogger(ValUtils.class);
  private static final Name WILDCARD = Name.fromConstantString("*");

  /** A local copy of the verifier object. */
  private DnsSecVerifier verifier;

  private int[] digestPreference = null;
  private boolean digestHardenDowngrade = true;

  /** Creates a new instance of this class. */
  public ValUtils() {
    this.verifier = new DnsSecVerifier();
  }

  /**
   * Set the owner name of NSEC RRsets to the canonical name, i.e. the name that is <b>not</b>
   * expanded from a wildcard label.
   *
   * @param set The RRset to canonicalize.
   * @param sig The signature that validated this RRset.
   */
  public static void setCanonicalNsecOwner(SRRset set, RRSIGRecord sig) {
    if (set.getType() != Type.NSEC) {
      return;
    }

    Record nsec = set.first();
    int fqdnLabelCount = nsec.getName().labels() - 1; // don't count the root label
    if (nsec.getName().isWild()) {
      --fqdnLabelCount; // don't count the wildcard label
    }

    if (sig.getLabels() == fqdnLabelCount) {
      set.setName(nsec.getName());
    } else if (sig.getLabels() < fqdnLabelCount) {
      set.setName(nsec.getName().wild(sig.getSigner().labels() - sig.getLabels()));
    } else {
      throw new IllegalArgumentException("invalid nsec record");
    }
  }

  /**
   * Initialize the module. The recognized configuration value are
   *
   * <ul>
   *   <li>{@link #DIGEST_PREFERENCE}
   *   <li>{@link #DIGEST_HARDEN_DOWNGRADE}
   * </ul>
   *
   * .
   *
   * @param config The configuration data for this module.
   */
  public void init(Properties config) {
    String dp = config.getProperty(DIGEST_PREFERENCE);
    if (dp != null) {
      String[] dpdata = dp.split(",");
      this.digestPreference = new int[dpdata.length];
      for (int i = 0; i < dpdata.length; i++) {
        this.digestPreference[i] = Integer.parseInt(dpdata[i]);
        if (!isDigestSupported(this.digestPreference[i])) {
          throw new IllegalArgumentException("Unsupported digest ID in digest preferences");
        }
      }
    }

    this.digestHardenDowngrade = Boolean.parseBoolean(config.getProperty(DIGEST_HARDEN_DOWNGRADE));
  }

  /**
   * Given a response, classify ANSWER responses into a subtype.
   *
   * @param request The original query message.
   * @param m The response to classify.
   * @return A subtype ranging from UNKNOWN to NAMEERROR.
   */
  public static ResponseClassification classifyResponse(Message request, SMessage m) {
    // Normal Name Error's are easy to detect -- but don't mistake a CNAME
    // chain ending in NXDOMAIN.
    if (m.getRcode() == Rcode.NXDOMAIN && m.getCount(Section.ANSWER) == 0) {
      return ResponseClassification.NAMEERROR;
    }

    // check for referral: nonRD query and it looks like a nodata
    if (m.getCount(Section.ANSWER) == 0 && m.getRcode() != Rcode.NOERROR) {
      // SOA record in auth indicates it is NODATA instead.
      // All validation requiring NODATA messages have SOA in
      // authority section.
      // uses fact that answer section is empty
      boolean sawNs = false;
      for (RRset set : m.getSectionRRsets(Section.AUTHORITY)) {
        if (set.getType() == Type.SOA) {
          return ResponseClassification.NODATA;
        }

        if (set.getType() == Type.DS) {
          return ResponseClassification.REFERRAL;
        }

        if (set.getType() == Type.NS) {
          sawNs = true;
        }
      }

      return sawNs ? ResponseClassification.REFERRAL : ResponseClassification.NODATA;
    }

    // root referral where NS set is in the answer section
    if (m.getSectionRRsets(Section.AUTHORITY).size() == 0
        && m.getSectionRRsets(Section.ANSWER).size() == 1
        && m.getRcode() == Rcode.NOERROR
        && m.getSectionRRsets(Section.ANSWER).get(0).getType() == Type.NS
        && !m.getSectionRRsets(Section.ANSWER)
            .get(0)
            .getName()
            .equals(request.getQuestion().getName())) {
      return ResponseClassification.REFERRAL;
    }

    // dump bad messages
    if (m.getRcode() != Rcode.NOERROR && m.getRcode() != Rcode.NXDOMAIN) {
      return ResponseClassification.UNKNOWN;
    }

    // Next is NODATA
    if (m.getCount(Section.ANSWER) == 0) {
      return ResponseClassification.NODATA;
    }

    // We distinguish between CNAME response and other positive/negative
    // responses because CNAME answers require extra processing.
    int qtype = m.getQuestion().getType();

    // We distinguish between ANY and CNAME or POSITIVE because ANY
    // responses are validated differently.
    if (qtype == Type.ANY) {
      return ResponseClassification.ANY;
    }

    boolean hadCname = false;
    for (RRset set : m.getSectionRRsets(Section.ANSWER)) {
      if (set.getType() == qtype) {
        return ResponseClassification.POSITIVE;
      }

      if (set.getType() == Type.CNAME || set.getType() == Type.DNAME) {
        hadCname = true;
        if (qtype == Type.DS) {
          return ResponseClassification.CNAME;
        }
      }
    }

    if (hadCname) {
      if (m.getRcode() == Rcode.NXDOMAIN) {
        return ResponseClassification.CNAME_NAMEERROR;
      } else {
        return ResponseClassification.CNAME_NODATA;
      }
    }

    logger.warn("Failed to classify response message:\n" + m);
    return ResponseClassification.UNKNOWN;
  }

  /**
   * Given a DS rrset and a DNSKEY rrset, match the DS to a DNSKEY and verify the DNSKEY rrset with
   * that key.
   *
   * @param dnskeyRrset The DNSKEY rrset to match against. The security status of this rrset will be
   *     updated on a successful verification.
   * @param dsRrset The DS rrset to match with. This rrset must already be trusted.
   * @param badKeyTTL The TTL [s] for keys determined to be bad.
   * @param date The date against which to verify the rrset.
   * @return a KeyEntry. This will either contain the now trusted dnskey RRset, a "null" key entry
   *     indicating that this DS rrset/DNSKEY pair indicate an secure end to the island of trust
   *     (i.e., unknown algorithms), or a "bad" KeyEntry if the dnskey RRset fails to verify. Note
   *     that the "null" response should generally only occur in a private algorithm scenario:
   *     normally this sort of thing is checked before fetching the matching DNSKEY rrset.
   */
  public KeyEntry verifyNewDNSKEYs(
      SRRset dnskeyRrset, SRRset dsRrset, long badKeyTTL, Instant date) {
    if (!atLeastOneDigestSupported(dsRrset)) {
      KeyEntry ke =
          KeyEntry.newNullKeyEntry(dsRrset.getName(), dsRrset.getDClass(), dsRrset.getTTL());
      ke.setBadReason(R.get("failed.ds.nodigest", dsRrset.getName()));
      return ke;
    }

    if (!atLeastOneSupportedAlgorithm(dsRrset)) {
      KeyEntry ke =
          KeyEntry.newNullKeyEntry(dsRrset.getName(), dsRrset.getDClass(), dsRrset.getTTL());
      ke.setBadReason(R.get("failed.ds.noalg", dsRrset.getName()));
      return ke;
    }

    int favoriteDigestID = this.favoriteDSDigestID(dsRrset);
    for (Record dsr : dsRrset.rrs()) {
      DSRecord ds = (DSRecord) dsr;
      if (this.digestHardenDowngrade && ds.getDigestID() != favoriteDigestID) {
        continue;
      }

      DNSKEY:
      for (Record dsnkeyr : dnskeyRrset.rrs()) {
        DNSKEYRecord dnskey = (DNSKEYRecord) dsnkeyr;

        // Skip DNSKEYs that don't match the basic criteria.
        if (ds.getFootprint() != dnskey.getFootprint()
            || ds.getAlgorithm() != dnskey.getAlgorithm()) {
          continue;
        }

        // Convert the candidate DNSKEY into a hash using the same DS
        // hash algorithm.
        DSRecord keyDigest = new DSRecord(Name.root, ds.getDClass(), 0, ds.getDigestID(), dnskey);
        byte[] keyHash = keyDigest.getDigest();
        byte[] dsHash = ds.getDigest();

        // see if there is a length mismatch (unlikely)
        if (keyHash.length != dsHash.length) {
          continue;
        }

        for (int k = 0; k < keyHash.length; k++) {
          if (keyHash[k] != dsHash[k]) {
            continue DNSKEY;
          }
        }

        // Otherwise, we have a match! Make sure that the DNSKEY
        // verifies *with this key*.
        SecurityStatus res = this.verifier.verify(dnskeyRrset, dnskey, date);
        if (res == SecurityStatus.SECURE) {
          logger.trace("DS matched DNSKEY.");
          dnskeyRrset.setSecurityStatus(SecurityStatus.SECURE);
          return KeyEntry.newKeyEntry(dnskeyRrset);
        }

        // If it didn't validate with the DNSKEY, try the next one!
      }
    }

    // If any were understandable, then it is bad.
    KeyEntry badKey = KeyEntry.newBadKeyEntry(dsRrset.getName(), dsRrset.getDClass(), badKeyTTL);
    badKey.setBadReason(R.get("dnskey.no_ds_match"));
    return badKey;
  }

  /**
   * Gets the digest ID for the favorite (best) algorithm that is support in a given DS set.
   *
   * <p>The order of preference can be configured with the property {@value #DIGEST_PREFERENCE}. If
   * the property is not set, the highest supported number is returned.
   *
   * @param dsset The DS set to check for the favorite algorithm.
   * @return The favorite digest ID or 0 if none is supported. 0 is not a known digest ID.
   */
  int favoriteDSDigestID(SRRset dsset) {
    if (this.digestPreference == null) {
      int max = 0;
      for (Record r : dsset.rrs()) {
        DSRecord ds = (DSRecord) r;
        if (ds.getDigestID() > max
            && isDigestSupported(ds.getDigestID())
            && isAlgorithmSupported(ds.getAlgorithm())) {
          max = ds.getDigestID();
        }
      }

      return max;
    } else {
      for (int preference : this.digestPreference) {
        for (Record r : dsset.rrs()) {
          DSRecord ds = (DSRecord) r;
          if (ds.getDigestID() == preference) {
            return ds.getDigestID();
          }
        }
      }
    }

    return 0;
  }

  /**
   * Given an SRRset that is signed by a DNSKEY found in the key_rrset, verify it. This will return
   * the status (either BOGUS or SECURE) and set that status in rrset.
   *
   * @param rrset The SRRset to verify.
   * @param keyRrset The set of keys to verify against.
   * @param date The date against which to verify the rrset.
   * @return The status (BOGUS or SECURE).
   */
  public SecurityStatus verifySRRset(SRRset rrset, SRRset keyRrset, Instant date) {
    String rrsetName =
        rrset.getName()
            + "/"
            + Type.string(rrset.getType())
            + "/"
            + DClass.string(rrset.getDClass());

    if (rrset.getSecurityStatus() == SecurityStatus.SECURE) {
      logger.trace("verifySRRset: rrset <" + rrsetName + "> previously found to be SECURE");
      return SecurityStatus.SECURE;
    }

    SecurityStatus status = this.verifier.verify(rrset, keyRrset, date);
    if (status != SecurityStatus.SECURE) {
      logger.debug("verifySRRset: rrset <" + rrsetName + "> found to be BAD");
      status = SecurityStatus.BOGUS;
    } else {
      logger.trace("verifySRRset: rrset <" + rrsetName + "> found to be SECURE");
    }

    rrset.setSecurityStatus(status);
    return status;
  }

  /**
   * Determine by looking at a signed RRset whether or not the RRset name was the result of a
   * wildcard expansion. If so, return the name of the generating wildcard.
   *
   * @param rrset The rrset to chedck.
   * @return the wildcard name, if the rrset was synthesized from a wildcard. null if not.
   */
  public static Name rrsetWildcard(RRset rrset) {
    List<RRSIGRecord> sigs = rrset.sigs();
    RRSIGRecord firstSig = sigs.get(0);

    // check rest of signatures have identical label count
    for (int i = 1; i < sigs.size(); i++) {
      if (sigs.get(i).getLabels() != firstSig.getLabels()) {
        throw new RuntimeException("failed.wildcard.label_count_mismatch");
      }
    }

    // if the RRSIG label count is shorter than the number of actual labels,
    // then this rrset was synthesized from a wildcard.
    // Note that the RRSIG label count doesn't count the root label.
    Name wn = rrset.getName();

    // skip a leading wildcard label in the dname (RFC4035 2.2)
    if (rrset.getName().isWild()) {
      wn = new Name(wn, 1);
    }

    int labelDiff = (wn.labels() - 1) - firstSig.getLabels();
    if (labelDiff > 0) {
      return wn.wild(labelDiff);
    }

    return null;
  }

  /**
   * Finds the longest domain name in common with the given name.
   *
   * @param domain1 The first domain to process.
   * @param domain2 The second domain to process.
   * @return The longest label in common of domain1 and domain2. The least common name is the root.
   */
  public static Name longestCommonName(Name domain1, Name domain2) {
    int l = Math.min(domain1.labels(), domain2.labels());
    domain1 = new Name(domain1, domain1.labels() - l);
    domain2 = new Name(domain2, domain2.labels() - l);
    for (int i = 0; i < l - 1; i++) {
      Name ns1 = new Name(domain1, i);
      if (ns1.equals(new Name(domain2, i))) {
        return ns1;
      }
    }

    return Name.root;
  }

  /**
   * Is the first Name strictly a subdomain of the second name (i.e., below but not equal to).
   *
   * @param domain1 The first domain to process.
   * @param domain2 The second domain to process.
   * @return True when domain1 is a strict subdomain of domain2.
   */
  public static boolean strictSubdomain(Name domain1, Name domain2) {
    if (domain1.labels() <= domain2.labels()) {
      return false;
    }

    return new Name(domain1, domain1.labels() - domain2.labels()).equals(domain2);
  }

  /**
   * Determines the 'closest encloser' - the name that has the most common labels between <code>
   * domain</code> and ({@link NSECRecord#getName()} or {@link NSECRecord#getNext()}).
   *
   * @param domain The name for which the closest encloser is queried.
   * @param owner The beginning of the covering {@link Name} to check.
   * @param next The end of the covering {@link Name} to check.
   * @return The closest encloser name of <code>domain</code> as defined by {@code owner} and {@code
   *     next}.
   */
  public static Name closestEncloser(Name domain, Name owner, Name next) {
    Name n1 = longestCommonName(domain, owner);
    Name n2 = longestCommonName(domain, next);

    return (n1.labels() > n2.labels()) ? n1 : n2;
  }

  /**
   * Gets the closest encloser of <code>domain</code> prepended with a wildcard label.
   *
   * @param domain The name for which the wildcard closest encloser is demanded.
   * @param set The RRset containing {@code nsec} to check.
   * @param nsec The covering NSEC that defines the encloser.
   * @return The wildcard closest encloser name of <code>domain</code> as defined by <code>nsec
   *     </code>.
   * @throws NameTooLongException If adding the wildcard label to the closest encloser results in an
   *     invalid name.
   */
  public static Name nsecWildcard(Name domain, SRRset set, NSECRecord nsec)
      throws NameTooLongException {
    Name origin = closestEncloser(domain, set.getName(), nsec.getNext());
    return Name.concatenate(WILDCARD, origin);
  }

  /**
   * Determine if the given NSEC proves a NameError (NXDOMAIN) for a given qname.
   *
   * @param set The RRset that contains the NSEC.
   * @param nsec The NSEC to check.
   * @param qname The qname to check against.
   * @return true if the NSEC proves the condition.
   */
  public static boolean nsecProvesNameError(SRRset set, NSECRecord nsec, Name qname) {
    Name owner = set.getName();
    Name next = nsec.getNext();

    // If NSEC owner == qname, then this NSEC proves that qname exists.
    if (qname.equals(owner)) {
      return false;
    }

    // deny overreaching NSECs
    if (!next.subdomain(set.getSignerName())) {
      return false;
    }

    // If NSEC is a parent of qname, we need to check the type map
    // If the parent name has a DNAME or is a delegation point, then this
    // NSEC is being misused.
    if (qname.subdomain(owner)) {
      if (nsec.hasType(Type.DNAME)) {
        return false;
      }

      if (nsec.hasType(Type.NS) && !nsec.hasType(Type.SOA)) {
        return false;
      }
    }

    if (owner.equals(next)) {
      // this nsec is the only nsec: zone.name NSEC zone.name
      // it disproves everything else but only for subdomains of that zone
      if (strictSubdomain(qname, next)) {
        return true;
      }
    } else if (owner.compareTo(next) > 0) {
      // this is the last nsec, ....(bigger) NSEC zonename(smaller)
      // the names after the last (owner) name do not exist
      // there are no names before the zone name in the zone
      // but the qname must be a subdomain of the zone name(next).
      if (owner.compareTo(qname) < 0 && strictSubdomain(qname, next)) {
        return true;
      }
    } else {
      // regular NSEC, (smaller) NSEC (larger)
      if (owner.compareTo(qname) < 0 && qname.compareTo(next) < 0) {
        return true;
      }
    }

    return false;
  }

  /**
   * Determine if a NSEC record proves the non-existence of a wildcard that could have produced
   * qname.
   *
   * @param set The RRset of the NSEC record.
   * @param nsec The nsec record to check.
   * @param qname The qname to check against.
   * @return true if the NSEC proves the condition.
   */
  public static boolean nsecProvesNoWC(SRRset set, NSECRecord nsec, Name qname) {
    Name ce = closestEncloser(qname, set.getName(), nsec.getNext());
    int labelsToStrip = qname.labels() - ce.labels();
    if (labelsToStrip > 0) {
      Name wcName = qname.wild(labelsToStrip);
      return nsecProvesNameError(set, nsec, wcName);
    }

    return false;
  }

  /**
   * Container for responses of {@link ValUtils#nsecProvesNodata(SRRset, NSECRecord, Name, int)}.
   */
  public static class NsecProvesNodataResponse {
    boolean result;
    Name wc;
  }

  /**
   * Determine if a NSEC proves the NOERROR/NODATA conditions. This will also handle the empty
   * non-terminal (ENT) case and partially handle the wildcard case. If the ownername of 'nsec' is a
   * wildcard, the validator must still be provided proof that qname did not directly exist and that
   * the wildcard is, in fact, *.closest_encloser.
   *
   * @param set The RRset of the NSEC record.
   * @param nsec The NSEC to check
   * @param qname The query name to check against.
   * @param qtype The query type to check against.
   * @return true if the NSEC proves the condition.
   */
  public static NsecProvesNodataResponse nsecProvesNodata(
      SRRset set, NSECRecord nsec, Name qname, int qtype) {
    NsecProvesNodataResponse result = new NsecProvesNodataResponse();
    if (!set.getName().equals(qname)) {
      // empty-non-terminal checking.
      // Done before wildcard, because this is an exact match,
      // and would prevent a wildcard from matching.

      // If the nsec is proving that qname is an ENT, the nsec owner will
      // be less than qname, and the next name will be a child domain of
      // the qname.
      if (strictSubdomain(nsec.getNext(), qname) && set.getName().compareTo(qname) < 0) {
        result.result = true;
        return result;
      }

      // Wildcard checking:
      // If this is a wildcard NSEC, make sure that a) it was possible to
      // have generated qname from the wildcard and b) the type map does
      // not contain qtype. Note that this does NOT prove that this
      // wildcard was the applicable wildcard.
      if (set.getName().isWild()) {
        // the is the purported closest encloser.
        Name ce = new Name(set.getName(), 1);

        // The qname must be a strict subdomain of the closest encloser,
        // and the qtype must be absent from the type map.
        if (strictSubdomain(qname, ce)) {
          if (nsec.hasType(Type.CNAME)) {
            // should have gotten the wildcard CNAME
            result.result = false;
            return result;
          }

          if (nsec.hasType(Type.NS) && !nsec.hasType(Type.SOA)) {
            // wrong parentside (wildcard) NSEC used, and it really
            // should not exist anyway:
            // http://tools.ietf.org/html/rfc4592#section-4.2
            result.result = false;
            return result;
          }

          if (nsec.hasType(qtype)) {
            result.result = false;
            return result;
          }
        }

        result.wc = ce;
        result.result = true;
        return result;
      }

      // Otherwise, this NSEC does not prove ENT, so it does not prove
      // NODATA.
      result.result = false;
      return result;
    }

    // If the qtype exists, then we should have gotten it.
    if (nsec.hasType(qtype)) {
      result.result = false;
      return result;
    }

    // if the name is a CNAME node, then we should have gotten the CNAME
    if (nsec.hasType(Type.CNAME)) {
      result.result = false;
      return result;
    }

    // If an NS set exists at this name, and NOT a SOA (so this is a zone
    // cut, not a zone apex), then we should have gotten a referral (or we
    // just got the wrong NSEC).
    // The reverse of this check is used when qtype is DS, since that
    // must use the NSEC from above the zone cut.
    if (qtype != Type.DS && nsec.hasType(Type.NS) && !nsec.hasType(Type.SOA)) {
      result.result = false;
      return result;
    } else if (qtype == Type.DS && nsec.hasType(Type.SOA) && !Name.root.equals(qname)) {
      result.result = false;
      return result;
    }

    result.result = true;
    return result;
  }

  /**
   * Check DS absence. There is a NODATA reply to a DS that needs checking. NSECs can prove this is
   * not a delegation point, or successfully prove that there is no DS. Or this fails.
   *
   * @param request The request that generated this response.
   * @param response The response to validate.
   * @param keyRrset The key that validate the NSECs.
   * @param date The date against which to verify the response.
   * @return The NODATA proof along with the reason of the result.
   */
  public JustifiedSecStatus nsecProvesNodataDsReply(
      Message request, SMessage response, SRRset keyRrset, Instant date) {
    Name qname = request.getQuestion().getName();
    int qclass = request.getQuestion().getDClass();

    // If we have a NSEC at the same name, it must prove one of two
    // things
    // --
    // 1) this is a delegation point and there is no DS
    // 2) this is not a delegation point
    SRRset nsecRrset = response.findRRset(qname, Type.NSEC, qclass, Section.AUTHORITY);
    if (nsecRrset != null) {
      // The NSEC must verify, first of all.
      SecurityStatus status = this.verifySRRset(nsecRrset, keyRrset, date);
      if (status != SecurityStatus.SECURE) {
        return new JustifiedSecStatus(SecurityStatus.BOGUS, R.get("failed.ds.nsec"));
      }

      NSECRecord nsec = (NSECRecord) nsecRrset.first();
      status = ValUtils.nsecProvesNoDS(nsec, qname);
      switch (status) {
        case INSECURE: // this wasn't a delegation point.
          return new JustifiedSecStatus(status, R.get("failed.ds.nodelegation"));
        case SECURE: // this proved no DS.
          return new JustifiedSecStatus(status, R.get("insecure.ds.nsec"));
        default: // something was wrong.
          return new JustifiedSecStatus(status, R.get("failed.ds.nsec.hasdata"));
      }
    }

    // Otherwise, there is no NSEC at qname. This could be an ENT.
    // If not, this is broken.
    NsecProvesNodataResponse ndp = new NsecProvesNodataResponse();
    Name ce = null;
    boolean hasValidNSEC = false;
    NSECRecord wcNsec = null;
    for (SRRset set : response.getSectionRRsets(Section.AUTHORITY, Type.NSEC)) {
      SecurityStatus status = this.verifySRRset(set, keyRrset, date);
      if (status != SecurityStatus.SECURE) {
        return new JustifiedSecStatus(status, R.get("failed.ds.nsec.ent"));
      }

      NSECRecord nsec = (NSECRecord) set.rrs().get(0);
      ndp = ValUtils.nsecProvesNodata(set, nsec, qname, Type.DS);
      if (ndp.result) {
        hasValidNSEC = true;
        if (ndp.wc != null && nsec.getName().isWild()) {
          wcNsec = nsec;
        }
      }

      if (ValUtils.nsecProvesNameError(set, nsec, qname)) {
        ce = closestEncloser(qname, set.getName(), nsec.getNext());
      }
    }

    // The wildcard NODATA is 1 NSEC proving that qname does not exists (and
    // also proving what the closest encloser is), and 1 NSEC showing the
    // matching wildcard, which must be *.closest_encloser.
    if (ndp.wc != null && (ce == null || !ce.equals(ndp.wc))) {
      hasValidNSEC = false;
    }

    if (hasValidNSEC) {
      if (ndp.wc != null) {
        SecurityStatus status = nsecProvesNoDS(wcNsec, qname);
        return new JustifiedSecStatus(status, R.get("failed.ds.nowildcardproof"));
      }

      return new JustifiedSecStatus(SecurityStatus.INSECURE, R.get("insecure.ds.nsec.ent"));
    }

    return new JustifiedSecStatus(SecurityStatus.UNCHECKED, R.get("failed.ds.nonconclusive"));
  }

  /**
   * Checks if the authority section of a message contains at least one signed NSEC or NSEC3 record.
   *
   * @param message The message to inspect.
   * @return True if at least one record is found, false otherwise.
   */
  public boolean hasSignedNsecs(SMessage message) {
    for (SRRset set : message.getSectionRRsets(Section.AUTHORITY)) {
      if (set.getType() == Type.NSEC || set.getType() == Type.NSEC3) {
        if (!set.sigs().isEmpty()) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Determines whether the given {@link NSECRecord} proves that there is no {@link DSRecord} for
   * <code>qname</code>.
   *
   * @param nsec The NSEC that should prove the non-existence.
   * @param qname The name for which the prove is made.
   * @return {@link SecurityStatus#BOGUS} when the NSEC is from the child domain or indicates that
   *     there indeed is a DS record, {@link SecurityStatus#INSECURE} when there is not even a prove
   *     for a NS record, {@link SecurityStatus#SECURE} when there is no DS record.
   */
  public static SecurityStatus nsecProvesNoDS(NSECRecord nsec, Name qname) {
    // Could check to make sure the qname is a subdomain of nsec
    if ((nsec.hasType(Type.SOA) && !Name.root.equals(qname)) || nsec.hasType(Type.DS)) {
      // SOA present means that this is the NSEC from the child, not the
      // parent (so it is the wrong one) -> cannot happen because the
      // keyset is always from the parent zone and doesn't validate the
      // NSEC
      // DS present means that there should have been a positive response
      // to the DS query, so there is something wrong.
      return SecurityStatus.BOGUS;
    }

    if (!nsec.hasType(Type.NS)) {
      // If there is no NS at this point at all, then this doesn't prove
      // anything one way or the other.
      return SecurityStatus.INSECURE;
    }

    // Otherwise, this proves no DS.
    return SecurityStatus.SECURE;
  }

  /**
   * Determines if at least one of the DS records in the RRset has a supported algorithm.
   *
   * @param dsRRset The RR set to search in.
   * @return True when at least one DS record uses a supported algorithm, false otherwise.
   */
  static boolean atLeastOneSupportedAlgorithm(RRset dsRRset) {
    for (Record r : dsRRset.rrs()) {
      if (isAlgorithmSupported(((DSRecord) r).getAlgorithm())) {
        return true;
      }

      // do nothing, there could be another DS we understand
    }

    return false;
  }

  /**
   * Determines if the algorithm is supported.
   *
   * @param alg The algorithm to check.
   * @return True when the algorithm is supported, false otherwise.
   */
  static boolean isAlgorithmSupported(int alg) {
    switch (alg) {
      case Algorithm.RSAMD5:
        return false; // obsoleted by rfc6944
      case Algorithm.DSA:
      case Algorithm.DSA_NSEC3_SHA1:
      case Algorithm.RSASHA1:
      case Algorithm.RSA_NSEC3_SHA1:
      case Algorithm.RSASHA256:
      case Algorithm.RSASHA512:
      case Algorithm.ECDSAP256SHA256:
      case Algorithm.ECDSAP384SHA384:
        return true;
      default:
        return false;
    }
  }

  /**
   * Determines if at least one of the DS records in the RRset has a supported digest algorithm.
   *
   * @param dsRRset The RR set to search in.
   * @return True when at least one DS record uses a supported digest algorithm, false otherwise.
   */
  static boolean atLeastOneDigestSupported(RRset dsRRset) {
    for (Record r : dsRRset.rrs()) {
      if (isDigestSupported(((DSRecord) r).getDigestID())) {
        return true;
      }

      // do nothing, there could be another DS we understand
    }

    return false;
  }

  /**
   * Determines if the digest algorithm is supported.
   *
   * @param digestID the algorithm to check.
   * @return True when the digest algorithm is supported, false otherwise.
   */
  static boolean isDigestSupported(int digestID) {
    switch (digestID) {
      case Digest.SHA1:
      case Digest.SHA256:
      case Digest.SHA384:
        return true;
      default:
        return false;
    }
  }
}

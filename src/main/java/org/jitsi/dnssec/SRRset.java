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

package org.jitsi.dnssec;

import java.util.List;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;

/**
 * An extended version of {@link RRset} that adds the indication of DNSSEC security status.
 *
 * @author davidb
 */
public class SRRset extends RRset {
  private SecurityStatus securityStatus;
  private Name ownerName;

  /** Create a new, blank SRRset. */
  public SRRset() {
    super();
    this.securityStatus = SecurityStatus.UNCHECKED;
  }

  /**
   * Create a new SRRset with one record.
   *
   * @param r The record to add to the RRset.
   */
  public SRRset(Record r) {
    super(r);
    this.securityStatus = SecurityStatus.UNCHECKED;
  }

  /**
   * Create a new SRRset from an existing RRset. This SRRset will contain the same internal {@link
   * Record} objects as the original RRset.
   *
   * @param r The RRset to copy.
   */
  public SRRset(RRset r) {
    super(r);
    this.securityStatus = SecurityStatus.UNCHECKED;
  }

  /**
   * Create a new SRRset from an existing SRRset. This SRRset will contain the same internal {@link
   * Record} objects as the original SRRset.
   *
   * @param r The RRset to copy.
   */
  public SRRset(SRRset r) {
    super(r);
    this.securityStatus = r.securityStatus;
    this.ownerName = r.ownerName;
  }

  /**
   * Return the current security status (generally: {@link SecurityStatus#UNCHECKED}, {@link
   * SecurityStatus#BOGUS}, or {@link SecurityStatus#SECURE}).
   *
   * @return The security status for this set, {@link SecurityStatus#UNCHECKED} if it has never been
   *     set manually.
   */
  public SecurityStatus getSecurityStatus() {
    return this.securityStatus;
  }

  /**
   * Set the current security status for this SRRset.
   *
   * @param status The new security status for this set.
   */
  public void setSecurityStatus(SecurityStatus status) {
    this.securityStatus = status;
  }

  /** @return The "signer" name for this SRRset, if signed, or null if not. */
  public Name getSignerName() {
    List<RRSIGRecord> sigs = sigs();
    if (!sigs.isEmpty()) {
      return sigs.get(0).getSigner();
    }

    return null;
  }

  @Override
  public Name getName() {
    return this.ownerName == null ? super.getName() : this.ownerName;
  }

  /**
   * Set the name of the records.
   *
   * @param ownerName the {@link Name} to override the original name with.
   */
  public void setName(Name ownerName) {
    this.ownerName = ownerName;
  }
}

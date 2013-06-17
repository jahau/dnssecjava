<del>CNAME Handling
--------------
<del>The CNAME handling is terribly inefficient. A recursive nameserver is required
to deliver all intermediate results in the response to the original query. The
code however still splits up the query into each part and performs a query for
each CNAME till the end of the chain is reached.
This should be changed to follow the chain in the response of the original
query, but is not so easy because the validation only has the keys for each
original query.
A possible workaround would be to synthesize the intermediate responses from
the original query. Easy for positive responses, but for NXDOMAIN - which
NSEC(3)s are to be included...?

<del>DNAME Handling
--------------
<del>A DNAME causes validation failures during priming because the synthesized
CNAME is not considered valid. Some unit-tests are failing due to this.~~

API
---
- Provide the final failure reason as a (localizable) string

Code Coverage
-------------
- The code still has a lot of untested parts, especially NSEC3 with opt-out
  enabled.
- JaCoCo/EclEmma doesn't work with jmockit enabled at the same time.

Unit Tests
----------
- <del>The tests currently rely on an online connection to a recursive server and
  external zones. They must be able to run offline.
- <del>Some tests will start to fail after June 9, 2013 because the signature date
  is compared against the current system time. This must be changed to take
  the test authoring time. To make this possible DNSJAVA must probably be
  changed.

DNSJAVA
-------
- Fix the Maven project definition to build correctly with a local lib folder
  as it is not officially distributed on Maven central
- Version 2.1.5 contains a bug in the Name constructor and needs the patch
  from the users mailing-list: http://goo.gl/JPKYrs
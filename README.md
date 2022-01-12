#### JDK Client For Fabric8

This client "mostly" works.  However the are a few key issues:

* It will not work with websocket requests containing queries with encoded characters with the fix https://github.com/openjdk/jdk/commit/c07ce7eec71aefbd3cb624e03ca53f5148d01f19 which is available in Java 16 - https://bugs.java.com/bugdatabase/view_bug.do?bug_id=8245245


* There are what appear to be protocol issues with the mock server.  The BuildConfigTest won't complete because the mock server is not handling expect continue in a way that the client expects.  Essentially with it enabled it doesn't send continue because the socket policy does not match, then you see an EOF when the mockserver attempts to read body.


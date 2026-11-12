# Third-party components in the Windows x64 ZIP

The bridge source and adapter are MIT-licensed. The ZIP includes the following
separately licensed runtimes and dependencies.

- CPython 3.13.17, supplied by the Python Software Foundation. Its license and
  bundled component notices are in runtime/python/LICENSE.txt. Source and
  Windows distribution are available from https://www.python.org/downloads/release/python-31317/.
- Eclipse Temurin 17.0.19+10, trimmed with jlink to include jdk.attach.
  The runtime's legal directory and NOTICE contain its license and notices.
  The exact Temurin binary release and corresponding source downloads are
  available from https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.19%2B10.
  The JDK source revision recorded in the upstream build is 2c052b14e3f9.
- Python wheels and their upstream notices are in runtime/python/Lib/site-packages,
  including license files and metadata within each distribution's dist-info directory.

Live2D Cubism Editor and its libraries are installed separately by the user.

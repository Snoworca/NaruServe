# Third-party licenses

Written by `./gradlew -Prelease releaseLicenses` from the `runtimeClasspath` of the nine published modules; do not edit by hand. The default build fails when the rows differ from the resolved dependencies.

| dependency | license | url |
|---|---|---|
| com.google.code.gson:gson:2.14.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.errorprone:error_prone_annotations:2.48.0 | Apache 2.0 | http://www.apache.org/licenses/LICENSE-2.0.txt |
| com.snoworca.tinyhttpd:tinyhttpd-core:0.1.0-SNAPSHOT | BSD-3-Clause | https://github.com/Snoworca/TinyHttpd/blob/main/LICENSE |
| com.snoworca.tinyhttpd:tinyhttpd-websocket:0.1.0-SNAPSHOT | BSD-3-Clause | https://github.com/Snoworca/TinyHttpd/blob/main/LICENSE |
| org.jetbrains.kotlin:kotlin-stdlib:2.2.0 | The Apache License, Version 2.0 | http://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains:annotations:13.0 | The Apache Software License, Version 2.0 | http://www.apache.org/licenses/LICENSE-2.0.txt |
| org.slf4j:slf4j-api:1.7.36 | MIT License | http://www.opensource.org/licenses/mit-license.php |

## Provided by the container or the build, not shipped

Compile-only artifacts: the Servlet container or the build supplies them, NaruServe never ships them.

| dependency | license | url |
|---|---|---|
| javax.servlet:javax.servlet-api:3.1.0 | CDDL + GPLv2 with classpath exception | https://glassfish.dev.java.net/nonav/public/CDDL+GPL.html |
| javax.websocket:javax.websocket-api:1.1 | Dual license consisting of the CDDL v1.1 and GPL v2 | https://glassfish.java.net/public/CDDL+GPL_1_1.html |
| org.jspecify:jspecify:1.0.0 | The Apache License, Version 2.0 | http://www.apache.org/licenses/LICENSE-2.0.txt |

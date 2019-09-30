
apply(from = "build-cache.gradle.kts")

rootProject.name = "pgjdb-ng-parent"

include(":settings-gen")

include(":spy")

include(":pgjdbc-ng")
project(":pgjdbc-ng").projectDir = file("driver")

include(":udt-gen")
include(":documentation")
include(":tools")

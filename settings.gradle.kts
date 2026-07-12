plugins {
    id("com.gradleup.nmcp.settings") version "1.6.1"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jimmer-rsql-support-root"

include("jimmer-rsql-support")

nmcpSettings {
    centralPortal {
        username = System.getenv("CENTRAL_USERNAME")
        password = System.getenv("CENTRAL_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}

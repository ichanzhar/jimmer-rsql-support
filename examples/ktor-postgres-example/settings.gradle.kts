rootProject.name = "ktor-postgres-example"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.github.ichanzhar:jimmer-rsql-support")).using(project(":jimmer-rsql-support"))
    }
}

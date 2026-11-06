import net.labymod.labygradle.common.extension.LabyModAnnotationProcessorExtension.ReferenceType

dependencies {
    labyProcessor()
    api(project(":api"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

labyModAnnotationProcessor {
    referenceType = ReferenceType.DEFAULT
}

tasks.test {
    useJUnitPlatform()
}

// The Laby annotation processor only makes sense for addon sources
tasks.compileTestJava {
    options.compilerArgs.add("-proc:none")
}

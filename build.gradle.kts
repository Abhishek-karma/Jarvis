plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    val testAgentConf = configurations.create("testByteBuddyAgent")
    dependencies.add("testByteBuddyAgent", "net.bytebuddy:byte-buddy-agent:1.14.17")
    tasks.withType<Test>().configureEach {
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
        doFirst {
            val agentJar = testAgentConf.singleFile.absolutePath
            jvmArgs("-javaagent:$agentJar")
        }
    }
}

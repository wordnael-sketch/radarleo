import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Assinatura do release: os dados vêm de variáveis de ambiente ou do local.properties
// (que está no .gitignore). Nada de senha neste arquivo. Ver android/ASSINATURA.md.
val propriedadesLocais = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun segredo(nome: String): String? = System.getenv(nome) ?: propriedadesLocais.getProperty(nome)

val temChaveDeRelease = segredo("RADARLEO_KEYSTORE") != null

android {
    namespace = "br.radarleo"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.radarleo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // URL raw do radares.json publicado pelo workflow (Passo 8). Para testar contra
        // outro endereço: ./gradlew assembleDebug -Pradarleo.urlBase=...
        val urlBase = providers.gradleProperty("radarleo.urlBase").get()
        buildConfigField("String", "URL_BASE", "\"$urlBase\"")
    }

    signingConfigs {
        if (temChaveDeRelease) {
            create("release") {
                storeFile = file(segredo("RADARLEO_KEYSTORE")!!)
                storePassword = segredo("RADARLEO_KEYSTORE_SENHA")
                keyAlias = segredo("RADARLEO_CHAVE_ALIAS")
                keyPassword = segredo("RADARLEO_CHAVE_SENHA")
            }
        }
    }

    buildTypes {
        release {
            // sem a chave configurada, o assembleRelease ainda funciona, mas sai sem assinatura
            if (temChaveDeRelease) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// A base embutida no APK é sempre a última gerada pelo ETL (output/radares.json):
// copiada para um diretório de assets gerado a cada build, sem duplicar o arquivo no git.
abstract class CopiarBaseTask : DefaultTask() {
    @get:InputFile
    abstract val origem: RegularFileProperty

    @get:OutputDirectory
    abstract val saida: DirectoryProperty

    @TaskAction
    fun copiar() {
        origem.get().asFile.copyTo(saida.get().file("radares.json").asFile, overwrite = true)
    }
}

val copiarBaseRadares = tasks.register<CopiarBaseTask>("copiarBaseRadares") {
    origem.set(rootProject.layout.projectDirectory.file("../output/radares.json"))
}

androidComponents {
    onVariants { variante ->
        variante.sources.assets?.addGeneratedSourceDirectory(copiarBaseRadares, CopiarBaseTask::saida)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.play.services.location)
    implementation(libs.androidx.fragment)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

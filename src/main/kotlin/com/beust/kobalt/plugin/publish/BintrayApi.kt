package com.beust.kobalt.plugin.publish

import com.beust.kobalt.KobaltException
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.Project
import com.beust.kobalt.maven.Gpg
import com.beust.kobalt.maven.Http
import com.beust.kobalt.maven.Md5
import com.beust.kobalt.misc.CountingFileRequestBody
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.error
import com.beust.kobalt.misc.log
import com.beust.kobalt.misc.warn
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.inject.assistedinject.Assisted
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.io.File
import java.lang.reflect.Type
import javax.annotation.Nullable
import javax.inject.Inject

class BintrayApi @Inject constructor(val http: Http,
                                     @Nullable @Assisted("username") val username: String?,
                                     @Nullable @Assisted("password") val password: String?,
                                     @Nullable @Assisted("org") val org: String?,
                                     val gpg: Gpg, val executors: KobaltExecutors) {

    companion object {
        const val BINTRAY_URL_API = "https://api.bintray.com"
    }

    interface IFactory {
        fun create(@Nullable @Assisted("username") username: String?,
                   @Nullable @Assisted("password") password: String?,
                   @Nullable @Assisted("org") org: String?): BintrayApi
    }

    interface Api {
        @GET("/packages/{owner}/maven/{package}")
        fun getPackage(@Path("owner") owner: String,
                       @Path("package") name: String): Call<BintrayResponse>

        @POST("/packages/{owner}/maven")
        fun createPackage(@Path("owner") owner: String,
                          @Body content: JsonObject): Call<BintrayResponse>

        @PUT("/content/{owner}/maven/{repo}/{version}/{group}/{artifact}/{version}/{name};publish={publish}")
        fun uploadArtifact(@Path("owner") owner: String,
                           @Path("repo") repo: String,
                           @Path("group", encoded = true) group: String,
                           @Path("artifact") artifact: String,
                           @Path("version") version: String,
                           @Path("name") name: String,
                           @Path("publish") publish: Int,
                           @Body file: File): Call<BintrayResponse>
    }

    private val service: Api

    init {
        val builder = OkHttpClient.Builder()
//                .addInterceptor(HttpLoggingInterceptor().apply {
//                    level = HttpLoggingInterceptor.Level.BASIC
//                })
        builder.interceptors().add(Interceptor { chain ->
            val original = chain.request();

            chain.proceed(original.newBuilder()
                    .header("Authorization", Credentials.basic(username, password))
                    .method(original.method(), original.body())
                    .build());
        })
        val okHttpClient = builder.build()

        service = Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(BintrayApi.BINTRAY_URL_API)
                .addConverterFactory(ConverterFactory())
                .build()
                .create(Api::class.java)
    }

    fun validatePackage(project: Project) {
        val execute = service.getPackage(org ?: username!!, project.name).execute()

        if (execute.errorBody()?.string()?.contains("'${project.name}' was not found") ?: false) {
            warn("Package does not exist on bintray.  Creating now.")
            val result = service.createPackage(org ?: username!!, buildPackageInfo(project))
                    .execute()
            if (result.errorBody() != null) {
                throw KobaltException("Error while creating package:\n" + result.errorBody().string())
            }
        }
    }

    private fun buildPackageInfo(project: Project): JsonObject {
        val jsonObject = JsonObject()
        jsonObject.addNonNull("name", project.name)
        jsonObject.addNonNull("desc", project.description)
        jsonObject.addNonNull("vcs_url", project.scm?.url)
        jsonObject.addNonNull("website_url", project.url)
        val licenses = JsonArray()
        project.licenses.forEach {
            licenses.add(it.name)
        }
        jsonObject.add("licenses", licenses)
        return jsonObject
    }

    fun uploadMaven(project: Project, files: List<File>, config: BintrayConfig): TaskResult {
        validatePackage(project)
        return upload(project, files, config, generateMd5 = true)
    }

    fun uploadFile(project: Project, file: File, config: BintrayConfig, generateMd5: Boolean = false) =
            upload(project, arrayListOf(file), config, generateMd5)

    private fun upload(project: Project, files: List<File>, config: BintrayConfig, generateMd5: Boolean): TaskResult {
        val filesToUpload = arrayListOf<File>()

        if (config.sign) {
            // Create the .asc files
            filesToUpload.addAll(gpg.runGpg(files))
        }
        files.forEach {
            filesToUpload.add(it)
            if (generateMd5) {
                // Create and upload the md5 for this file
                with(it) {
                    val md5: String = Md5.toMd5(this)
                    val md5File = File(path + ".md5")
                    md5File.writeText(md5)
                    filesToUpload.add(md5File)
                }
            }
        }

        val fileCount = filesToUpload.size
        if (fileCount > 0) {
            log(1, "  Found $fileCount artifacts to upload")
            val errorMessages = arrayListOf<String>()

            fun dots(total: Int, list: List<Boolean>, file: File? = null): String {
                val spaces: String = Array(total - list.size, { " " }).joinToString("")
                return "|" + list.map { if (it) "." else "X" }.joinToString("") + spaces +
                        (if (file != null) "| [ $file ]" else "|")
            }

            val results = arrayListOf<Boolean>()
            filesToUpload.forEachIndexed { i, file ->
                val owner = org ?: username!!
                val repo = project.name
                val group = project.group!!.replace('.', '/')
                val artifact = project.artifactId!!
                val version = project.version!!

                val result = service.uploadArtifact(owner, repo, group, artifact, version, file.name,
                        if (config.publish) 1 else 0, file)
                        .execute()
                val error = result.errorBody()?.string()
                if (result.errorBody() != null) {
                    errorMessages.add(error!!)
                    results.add(false)
                } else {
                    results.add(true)
                }

                log(1, "    Uploading ${i + 1} / $fileCount " + dots(fileCount, results, file), false)
            }
            val success = results
                    .filter { it }
                    .count()
            log(1, "    Uploaded $success / $fileCount " + dots(fileCount, results), false)
            log(1, "", true)
            if (errorMessages.isEmpty()) {
                return TaskResult()
            } else {
                error(" Errors while uploading:\n" + errorMessages.map { "    $it" }.joinToString("\n"))
                return TaskResult(false, errorMessages.joinToString("\n"))
            }
        } else {
            warn("Found no artifacts to upload")
            return TaskResult()
        }
    }

    class BintrayResponse(val jo: JsonObject?, val errorMessage: String?)

    fun JsonObject.addNonNull(name: String, value: String?) {
        if (value != null) {
            addProperty(name, value);
        }
    }

}

class ConverterFactory : Converter.Factory() {
    override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? {
        return GsonResponseBodyConverter(Gson(), Gson().getAdapter(TypeToken.get(type)))
    }

    override fun requestBodyConverter(type: Type, parameterAnnotations: Array<out Annotation>, methodAnnotations: Array<out Annotation>,
                                      retrofit: Retrofit?): Converter<*, RequestBody>? {
        return RequestBodyConverter()
    }
}

class GsonResponseBodyConverter(private val gson: Gson, private val adapter: TypeAdapter<out Any>) : Converter<ResponseBody, Any> {
    override fun convert(value: ResponseBody): Any {
        val jsonReader = gson.newJsonReader(value.charStream())
        try {
            return adapter.read(jsonReader)
        } finally {
            value.close()
        }
    }
}

class RequestBodyConverter : Converter<File, RequestBody> {
    override fun convert(value: File): RequestBody {
        return CountingFileRequestBody(value, "application/*", {  })
    }
}
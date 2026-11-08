package app.velin.core.data

import app.velin.core.network.*
import app.velin.core.sdk.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}
internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
internal fun url64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
internal class NewSession(val token: String, val user: String, val types: List<String>)

/** Attribute IDs and model version: entities/sys/TypeModels.js:3573-3648,4300-4373,8037-8306,11703-11742.
 * TypeMapper.ts:247-275 serializes values as strings and associations as lists, even cardinality One. */
internal class AuthService(private val baseUrl: String, private val transport: BinaryTransport) {
    suspend fun salt(login: String): Pair<ByteArray, KdfAlgorithm> {
        val result = call("saltservice", RequestMethod.GET, json("418" to "0", "419" to login))
        val algorithm = when (result.getString("2133")) {
            "0" -> KdfAlgorithm.Bcrypt128
            "1" -> KdfAlgorithm.Argon2id
            else -> error("Unsupported KDF")
        }
        return Base64.getDecoder().decode(result.getString("422")) to algorithm
    }
    suspend fun create(login: String, key: ByteArray, accessKey: ByteArray): NewSession {
        val result = call("sessionservice", RequestMethod.POST, json(
            "1212" to "0", "1213" to login, "1214" to url64(sha256(key)),
            "1215" to "Velin Android", "1216" to b64(accessKey), "1217" to null,
            "1417" to null, "1218" to JSONArray()))
        val challenges = result.getJSONArray("1222")
        val token = result.getString("1221")
        sessionIds(token)
        val users = result.getJSONArray("1223")
        require(users.length() == 1 && users.getString(0).isNotEmpty())
        return NewSession(token, users.getString(0),
            (0 until challenges.length()).map { challenges.getJSONObject(it).getString("1189") })
    }
    suspend fun pending(token: String): Boolean = when (
        call("secondfactorauthservice", RequestMethod.GET, json("1234" to "0", "1235" to token)).getString("1238")) {
        "1" -> true
        "0" -> false
        else -> error("Invalid pending flag")
    }
    suspend fun totp(token: String, code: String) {
        call("secondfactorauthservice", RequestMethod.POST, json("542" to "0", "1230" to "1",
            "1243" to code, "1231" to JSONArray(), "1905" to JSONArray(), "1232" to sessionAssociation(token)))
    }
    suspend fun cancel(token: String) {
        try { call("secondfactorauthservice", RequestMethod.DELETE,
            json("1756" to "0", "1757" to sessionAssociation(token))) }
        catch (e: ProtocolException.Http) { if (e.status != 404 && e.status != 423) throw e }
    }
    suspend fun revoke(token: String) {
        val ids = sessionIds(token)
        try { request("$baseUrl/rest/sys/Session/${ids[0]}/${ids[1]}", RequestMethod.DELETE, null, token) }
        catch (_: ProtocolException.Unauthorized) { }
        catch (e: ProtocolException.Http) { if (e.status != 404) throw e }
    }
    private suspend fun call(service: String, method: RequestMethod, data: JSONObject): JSONObject {
        // RestClient.ts:70-71 moves GET JSON into the _body query parameter.
        val payload = data.toString()
        val url = "$baseUrl/rest/sys/$service" + if (method == RequestMethod.GET)
            "?_body=" + URLEncoder.encode(payload, "UTF-8") else ""
        val response = request(url, method, if (method == RequestMethod.GET) null else payload.toByteArray())
        return response.body?.takeIf { it.isNotEmpty() }?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
    }
    private suspend fun request(url: String, method: RequestMethod, body: ByteArray?, token: String? = null): BinaryResponse {
        val headers = mutableMapOf("v" to TutaRelease.SYS_MODEL_VERSION.toString(), "cv" to SDK_CLIENT_VERSION, "cp" to ANDROID_MAIL_PLATFORM,
            "Accept" to "application/json", "Content-Type" to "application/json")
        token?.let { headers["accessToken"] = it }
        return transport.request(BinaryRequest(url, method, headers, body)).also { ServiceClient.checkStatus(it.status) }
    }
    private fun sessionAssociation(token: String) = JSONArray().put(JSONArray(sessionIds(token)))
    companion object {
        // LoginFacade.ts:1185-1194; generated_id.rs uses nine bytes and sortable base64 for the list ID.
        fun sessionIds(token: String): List<String> {
            val bytes = Base64.getUrlDecoder().decode(token)
            require(bytes.size > 9)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val sortable = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
            return listOf(b64(bytes.copyOfRange(0, 9)).map { sortable[alphabet.indexOf(it)] }.joinToString(""),
                url64(sha256(bytes.copyOfRange(9, bytes.size))))
        }
    }
}

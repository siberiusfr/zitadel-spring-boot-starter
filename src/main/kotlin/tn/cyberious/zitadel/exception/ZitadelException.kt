package tn.cyberious.zitadel.exception

class ZitadelException(
    message: String,
    val httpStatus: Int,
    val zitadelCode: String? = null,
) : RuntimeException(message)

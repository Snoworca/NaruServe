package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.Request

// @req IR-KT-002
/**
 * The JSON body as a non-null [T], as `val user = req.body<User>()`; a JSON `null` body is a client error
 * ([HttpException] `400`). `T::class.java` drops type arguments, so use `req.body(TypeRef)` for generic bodies.
 */
inline fun <reified T : Any> Request.body(): T = body(T::class.java) ?: throw HttpException(400, "JSON body is null")

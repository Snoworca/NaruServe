package com.snoworca.naruserve.kotlin

import com.snoworca.naruserve.HttpException
import com.snoworca.naruserve.Request
import com.snoworca.naruserve.TypeRef

// @req IR-KT-002
// @req FR-DX-006
/**
 * The JSON body as a non-null [T], as `val user = req.body<User>()`; a JSON `null` body is a client error
 * ([HttpException] `400`). [TypeRef] preserves complete type arguments, including nested collections.
 */
inline fun <reified T : Any> Request.body(): T = body(object : TypeRef<T>() {}) ?: throw HttpException(400, "JSON body is null")

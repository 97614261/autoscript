package com.autoscript.project.store

/** Stable identity for editor navigation, never inferred from translated error text. */
class ProjectInterfaceReferenceException(val controlId: String, cause: IllegalArgumentException) :
    IllegalArgumentException(cause.message, cause)

package com.github.ichanzhar.rsql.jimmer.utils

public object JavaTypeUtil {
    private val primitiveWrappers: Map<String, Class<out Any>> =
        mapOf(
            "boolean" to Boolean::class.javaObjectType,
            "byte" to Byte::class.javaObjectType,
            "char" to Character::class.java,
            "double" to Double::class.javaObjectType,
            "float" to Float::class.javaObjectType,
            "int" to Integer::class.java,
            "long" to Long::class.javaObjectType,
            "short" to Short::class.javaObjectType,
        )

    public fun getPropertyJavaType(propertyJavaType: Class<out Any>?): Class<out Any>? =
        primitiveWrappers[propertyJavaType?.name] ?: propertyJavaType
}

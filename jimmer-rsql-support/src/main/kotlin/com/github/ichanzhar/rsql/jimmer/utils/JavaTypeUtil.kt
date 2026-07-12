package com.github.ichanzhar.rsql.jimmer.utils

public object JavaTypeUtil {
    private val primitiveWrappers: Map<String, Class<out Any>> =
        mapOf(
            "java.lang.Boolean" to Boolean::class.java,
            "boolean" to Boolean::class.java,
            "byte" to Byte::class.java,
            "char" to Character::class.java,
            "double" to Double::class.java,
            "float" to Float::class.java,
            "int" to Integer::class.java,
            "long" to Long::class.java,
            "short" to Short::class.java,
        )

    public fun getPropertyJavaType(propertyJavaType: Class<out Any>?): Class<out Any>? =
        primitiveWrappers[propertyJavaType?.name] ?: propertyJavaType
}

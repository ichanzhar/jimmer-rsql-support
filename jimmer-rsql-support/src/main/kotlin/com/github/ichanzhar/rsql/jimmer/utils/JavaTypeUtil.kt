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
            "java.lang.Integer" to Int::class.java,
            "java.lang.Long" to Long::class.java,
            "java.lang.Double" to Double::class.java,
            "java.lang.Float" to Float::class.java,
            "java.lang.Short" to Short::class.java,
            "java.lang.Byte" to Byte::class.java,
            "java.lang.Character" to Char::class.java,
        )

    public fun getPropertyJavaType(propertyJavaType: Class<out Any>?): Class<out Any>? =
        primitiveWrappers[propertyJavaType?.name] ?: propertyJavaType
}

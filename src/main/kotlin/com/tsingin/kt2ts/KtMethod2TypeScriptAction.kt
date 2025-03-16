package com.tsingin.kt2ts

import com.intellij.designer.clipboard.SimpleTransferable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import java.awt.datatransfer.DataFlavor
import kotlin.text.iterator

class KtMethod2TypeScriptAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val element = e.getData(CommonDataKeys.PSI_ELEMENT) ?: return
        val method =
            if (element is KtFunction) element else PsiTreeUtil.getParentOfType(element, KtFunction::class.java)
                ?: return
        val ktClass = method.containingClass() ?: return

        try {
            val meta = parseControllerMethod(method, ktClass)
            val tsCode = generateTSCode(meta, method, ktClass)
            copyToClipboard(tsCode)
        } catch (ex: Exception) {
            Messages.showErrorDialog("生成失败: ${ex.message}", "错误")
        }
    }

    private fun parseControllerMethod(method: KtFunction, klass: KtClass): ApiMeta {
        val httpMeta = parseHttpAnnotation(method)
        val classPath = parseClassPath(klass)
        var fullPath = "${classPath}/${httpMeta.path}"
        // 去掉多余的斜杠
        while ("//" in fullPath) {
            fullPath = fullPath.replace("//", "/")
        }

        return ApiMeta(
            methodName = method.name ?: "",
            fullPath = fullPath,
            httpMethod = httpMeta.method,
            requestType = method.valueParameters.joinToString(", ") { it.typeReference?.text ?: "Unknown" },
            responseType = method.typeReference?.text,
            isPagination = isPaginationMethod(method)
        )
    }

    private fun parseHttpAnnotation(method: KtFunction): HttpMeta {
        val mappingAnnotations = listOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")
        val annotation = method.annotationEntries.firstOrNull {
            it.shortName?.asString() in mappingAnnotations
        } ?: throw Exception("未找到HTTP映射注解，请确保方法上标注了有效的HTTP注解（如 @GetMapping）。")

        val path = annotation.findAttributeValue("value")?.trim('"') ?: ""
        val methodAttr = annotation.shortName?.asString()
            ?.takeIf { it in mappingAnnotations }
            ?.replace("Mapping", "")
            ?.uppercase()
            ?: "GET"

        return HttpMeta(methodAttr, path)
    }

    private fun parseClassPath(klass: KtClass): String {
        val find = klass.annotationEntries.find { it.shortName?.asString() == "RequestMapping" }
        return find?.findAttributeValue("value")?.trim('"') ?: ""
    }

    private fun isPaginationMethod(method: KtFunction): Boolean {
        val hasPageReqParam = method.valueParameters.any {
            it.typeReference?.text?.lowercase()?.contains("page") == true
        }
        return hasPageReqParam
    }

    private fun generateTSCode(meta: ApiMeta, method: KtFunction, klass: KtClass): String {
        val types = collectDependentTypes(method, klass)
        val interfaces = generateInterfaces(types)

        val functionName = method.name ?: "unknownFunction"
        val responseType = convertTsType(method.typeReference?.text)
        println("meta:"+meta)
        // 解析路径中的动态占位符
        val pathSegments = meta.fullPath.split("/")
        println("meta.fullPath:"+meta.fullPath)
        println("pathSegments:"+pathSegments)
        val dynamicParams = mutableListOf<String>()
        val fullPathWithPlaceholders = pathSegments.joinToString("/") { segment ->
            if (segment.startsWith("{") && segment.endsWith("}")) {
                val paramName = segment.removeSurrounding("{", "}").trim()
                dynamicParams.add(paramName)
                "\${$paramName}"
            } else {
                segment
            }
        }

        // 确保路径末尾没有多余的字符（严格清理多余的大括号）
        val cleanedFullPath = fullPathWithPlaceholders.replace(Regex("\\{}+$"), "")
        // 分离参数类型
        val requestBodyParam = method.valueParameters.find { it.hasAnnotation("RequestBody") }
        val pathVariableParams = method.valueParameters.filter { it.hasAnnotation("PathVariable") }

        // 动态参数声明
        val dynamicParamDeclarations = dynamicParams.joinToString(", ") { "$it: string" }

        // 构建函数签名
        val functionSignature = buildString {
            append("export async function $functionName(\n")
            if (requestBodyParam != null) {
                append("  ${requestBodyParam.name}: ${requestBodyParam.typeReference?.text},\n")
            }
            if (dynamicParamDeclarations.isNotEmpty()) {
                append("  $dynamicParamDeclarations,\n")
            }
            append("  options?: any\n")
            append("): Promise<$responseType> {\n")
        }

        // 构建函数体
        val functionBody = """
        |// @ts-ignore
        |  return request<$responseType>(`${cleanedFullPath}`, {
        |    method: '${meta.httpMethod}',
        |    ${if (requestBodyParam != null) "data: ${requestBodyParam.name}," else ""}
        |    ...(options || {}),
        |  });
        |}
    """.trimMargin()

        return """
        |// 入参
        |${interfaces[0].joinToString("\n\n")}
        |
        |// 出参
        |${interfaces[1].joinToString("\n\n")}
        |
        |// 请求路径：${meta.fullPath}
        |// 请求方法：${meta.httpMethod}
        |$functionSignature
        |$functionBody
    """.trimMargin()
    }




    private fun collectDependentTypes(method: KtFunction, klass: KtClass): List<Set<KtClass>> {
        val typeCollector = mutableListOf<MutableSet<KtClass>>()
        val input = mutableSetOf<KtClass>()
        val output = mutableSetOf<KtClass>()
        val clazz = mutableSetOf<KtClass>()
        typeCollector.add(input)
        typeCollector.add(output)
        typeCollector.add(clazz)

        // 入参
        method.valueParameters.forEach { param ->
            param.typeReference?.let { collectNestedTypes(it, input) }
        }

        // 出参
        method.typeReference?.let { collectNestedTypes(it, output) }

        return typeCollector
    }

    private fun resolveType(typeReference: KtTypeReference?): KtClass? {
        return typeReference?.typeElement?.let { typeElement ->
            when (typeElement) {
                is KtUserType -> typeElement.referenceExpression?.mainReference?.resolve() as? KtClass
                else -> null
            }
        }
    }

    private fun collectNestedTypes(typeReference: KtTypeReference, typeCollector: MutableSet<KtClass>) {
        val resolvedType = resolveType(typeReference)

        if (resolvedType != null && !isBasicType(resolvedType) && typeCollector.add(resolvedType)) {
            resolvedType.getSuperTypeListEntries().forEach { superTypeEntry ->
                val superType = superTypeEntry.typeReference?.let { resolveType(it) }
                superType?.let { typeCollector.add(it) }
            }
        }

        if (typeReference.typeElement is KtUserType) {
            val userType = typeReference.typeElement as KtUserType
            userType.typeArguments.forEach { typeProjection ->
                typeProjection.typeReference?.let { collectNestedTypes(it, typeCollector) }
            }
        } else if (typeReference.typeElement is KtFunctionType) {
            val functionType = typeReference.typeElement as KtFunctionType
            functionType.parameters.forEach { param ->
                param.typeReference?.let { collectNestedTypes(it, typeCollector) }
            }
            functionType.returnTypeReference?.let { collectNestedTypes(it, typeCollector) }
        }
    }

    private fun isBasicType(ktClass: KtClass): Boolean {
        val fqName = ktClass.fqName?.asString()
        return fqName?.startsWith("kotlin.") == true
    }

    private fun generateInterfaces(ktclasses: List<Set<KtClass>>): List<List<String>> {
        val generatedTypes = mutableSetOf<String>()
        val interfaces = mutableListOf<MutableList<String>>()
        ktclasses.forEach { paramType ->
            val x = mutableListOf<String>()
            paramType.forEach { ktClass ->
                generateInterface(ktClass, generatedTypes, x)?.let { x.add(it) }
            }
            interfaces.add(x)
        }

        return interfaces
    }

    private fun generateInterface(
        klass: KtClass, generatedTypes: MutableSet<String>, interfaces: MutableList<String>
    ): String? {
        val className = klass.name ?: return null
        if (generatedTypes.contains(className)) return null
        generatedTypes.add(className)

        val props = mutableSetOf<String>()

        // 处理构造函数参数
        klass.primaryConstructor?.valueParameters?.forEach { param ->
            props.add("${param.name}${if (param.isNullable()) "?" else ""}: ${convertTsType(param.typeReference?.text)}")
        }

        // 处理类属性
        klass.getBody()?.properties?.forEach { property ->
            props.add("${property.name}${if (property.isNullable()) "?" else ""}: ${convertTsType(property.typeReference?.text)}")
        }

        // 处理父类或接口
        val superTypes = mutableSetOf<String>()
        klass.getSuperTypeListEntries().forEach { superTypeEntry ->
            val superType = superTypeEntry.typeReference?.let { resolveType(it) }
            superType?.let { superClass ->
                val superClassName = superClass.name
                if (superClassName != null && !generatedTypes.contains(superClassName)) {
                    superTypes.add(superClassName)
                    generateInterface(superClass, generatedTypes, interfaces)?.let { interfaces.add(it) }
                } else if (superClassName != null) {
                    superTypes.add(superClassName)
                }
            }
        }

        val extendsClause = if (superTypes.isNotEmpty()) " extends ${superTypes.joinToString(", ")}" else ""
        val typeParams = klass.typeParameters.joinToString(", ") { it.name ?: "T" }
        val genericClause = if (typeParams.isNotEmpty()) "<$typeParams>" else ""

        return """
        |export interface $className$genericClause$extendsClause {
        |  ${props.joinToString("\n  ")}
        |}
    """.trimMargin()
    }

    private fun convertTsType(kotlinType: String?): String {
        if (kotlinType == null) return "any"

        val index = kotlinType.indexOfFirst { it.isUpperCase() }
        var type = if (index != -1) kotlinType.substring(index) else ""

        return when {
            type.endsWith("?") -> {
                val innerType = type.removeSuffix("?")
                "${convertTsType(innerType)} | undefined"
            }

            type == "String" -> "string"
            type in listOf("Int", "Long", "Double", "Float") -> "number"
            type == "Boolean" -> "boolean"
            type.startsWith("List<") -> {
                val innerType = type.removeSurrounding("List<", ">")
                "${convertTsType(innerType)}[]"
            }

            type.startsWith("Map<") -> {
                val inner = type.removeSurrounding("Map<", ">")
                val parts = splitTopLevelCommas(inner)
                if (parts.size != 2) {
                    "Map<any, any>"
                } else {
                    val keyType = convertTsType(parts[0])
                    val valueType = convertTsType(parts[1])
                    "Map<$keyType, $valueType>"
                }
            }

            '<' in type -> {
                val mainType = type.substringBefore('<')
                val generics = type.substringAfter('<').removeSuffix(">")
                val tsGenerics = splitTopLevelCommas(generics).joinToString(", ") { convertTsType(it) }
                "$mainType<$tsGenerics>"
            }

            else -> type
        }
    }

    private fun splitTopLevelCommas(input: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var depth = 0

        for (char in input) {
            when (char) {
                '<' -> depth++
                '>' -> depth--
                ',' -> {
                    if (depth == 0) {
                        result.add(current.toString().trim())
                        current.clear()
                        continue
                    }
                }
            }
            current.append(char)
        }

        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }

        return result
    }


    private fun copyToClipboard(content: String) {
        try {
            CopyPasteManager.getInstance().setContents(SimpleTransferable(content, DataFlavor.stringFlavor))
        } catch (ex: Exception) {
            Messages.showErrorDialog("复制到剪贴板失败: ${ex.message}", "错误")
        }
    }

    override fun update(e: AnActionEvent) {
        val isControllerMethod = ReadAction.compute<Boolean, Throwable> {
            val element = e.getData(CommonDataKeys.PSI_ELEMENT)
            val method =
                if (element is KtFunction) element else PsiTreeUtil.getParentOfType(element, KtFunction::class.java)
            val containingClass = method?.containingClass()
            containingClass != null && (containingClass.hasAnnotation("RestController") || containingClass.hasAnnotation(
                "Controller"
            ))
        }

        e.presentation.isEnabledAndVisible = isControllerMethod
    }

    private data class ApiMeta(
        val methodName: String,
        val fullPath: String,
        val httpMethod: String,
        val requestType: String?,
        val responseType: String?,
        val isPagination: Boolean
    )

    private data class HttpMeta(val method: String, val path: String)
}

private fun KtParameter.isNullable(): Boolean {
    return typeReference?.text?.endsWith("?") == true || hasDefaultValue()
}

private fun KtProperty.isNullable(): Boolean {
    return typeReference?.text?.endsWith("?") == true
}

private fun KtFunction.hasAnnotation(name: String): Boolean {
    return annotationEntries.any { it.shortName?.asString() == name }
}

private fun KtParameter.hasAnnotation(name: String): Boolean {
    return annotationEntries.any { it.shortName?.asString() == name }
}

private fun KtAnnotationEntry.findAttributeValue(name: String): String? {
    return valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == name }
        ?.getArgumentExpression()?.text?.trim('"') ?: valueArguments.firstOrNull()?.getArgumentExpression()?.text?.trim(
        '"'
    )
}

private fun KtClass.hasAnnotation(name: String): Boolean {
    return annotationEntries.any { it.shortName?.asString() == name }
}
package dev.mmrlx.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

private const val ANNOTATION_FQN = "dev.mmrlx.thread.RootFunction"
private val ROOT_CALLABLE_CLASS = ClassName("dev.mmrlx.thread", "RootCallable")
private val ROOT_OPTIONS_CLASS = ClassName("dev.mmrlx.thread", "RootOptions")
private val ROOT_SCOPE_CLASS = ClassName("dev.mmrlx.thread.ktx", "RootScope")
private val ROOT_FLOW_FN = MemberName("dev.mmrlx.thread.ktx", "rootFlow")
private val ROOT_THREAD_FN = MemberName("dev.mmrlx.thread.ktx", "rootThread")
private val FLOW_CLASS = ClassName("kotlinx.coroutines.flow", "Flow")
private val PUBLISHED_API_CLASS = ClassName("kotlin", "PublishedApi")
private const val ROOT_OPTIONS_FQN = "dev.mmrlx.thread.RootOptions"

class RootCallableProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(ANNOTATION_FQN)
        val deferred = symbols.filterNot { it.validate() }.toList()

        symbols
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.validate() }
            .forEach { generateCallable(it) }

        return deferred
    }

    @OptIn(KspExperimental::class)
    private fun generateCallable(fn: KSFunctionDeclaration) {
        val fnName = fn.simpleName.asString()

        // Determine whether the function lives inside a companion object
        val parentDecl = fn.parentDeclaration
        val companionClass = if (parentDecl is com.google.devtools.ksp.symbol.KSClassDeclaration && parentDecl.isCompanionObject) {
            parentDecl.parentDeclaration as? com.google.devtools.ksp.symbol.KSClassDeclaration
        } else null

        // Derive class name: e.g. loadModules -> RootedLoadModules
        val className = "Rooted" + fnName.replaceFirstChar { it.uppercaseChar() }
        val pkg = "dev.mmrlx.threading"

        val returnType = fn.returnType?.resolve()
            ?: run {
                logger.error("@RootFunction function must have an explicit return type", fn)
                return
            }

        val returnTypeName = returnType.toTypeName()

        val params = fn.parameters

        // Detect whether the annotated function accepts a RootOptions parameter
        val rootOptionsParam = params.firstOrNull {
            it.type.resolve().declaration.qualifiedName?.asString() == ROOT_OPTIONS_FQN
        }
        // Non-RootOptions params — these become constructor fields
        val dataParams = params.filter { it != rootOptionsParam }

        val constructorBuilder = FunSpec.constructorBuilder()
        val properties = mutableListOf<PropertySpec>()

        for (param in dataParams) {
            val paramName = param.name!!.asString()
            val paramType = param.type.resolve().toTypeName()
            constructorBuilder.addParameter(paramName, paramType)
            properties += PropertySpec.builder(paramName, paramType)
                .initializer(paramName)
                .addModifiers(KModifier.PRIVATE)
                .build()
        }

        // call(options) override — passes options to the function only if it expects it
        val dataArgs = dataParams.joinToString(", ") { it.name!!.asString() }
        val callArgs = if (rootOptionsParam != null) {
            val optionsName = rootOptionsParam.name!!.asString()
            if (dataArgs.isEmpty()) optionsName else "$optionsName, $dataArgs"
        } else {
            dataArgs
        }
        // For companion object functions, call via EnclosingClass.fnName(args)
        // For top-level functions, call directly as fnName(args)
        val callReceiver = if (companionClass != null) {
            companionClass.simpleName.asString()
        } else null

        val callExpr = if (callReceiver != null) "$callReceiver.$fnName" else fnName
        val callBody = if (callArgs.isEmpty()) "$callExpr()" else "$callExpr($callArgs)"

        val callOverride = FunSpec.builder("call")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("options", ROOT_OPTIONS_CLASS)
            .returns(returnTypeName)
            .addStatement("return %L", callBody.replace(
                rootOptionsParam?.name?.asString() ?: "\u0000", "options"
            ))
            .build()

        val classSpec = TypeSpec.classBuilder(className)
            .addSuperinterface(ROOT_CALLABLE_CLASS.parameterizedBy(returnTypeName))
            .addSuperinterface(ClassName("java.io", "Serializable"))
            .primaryConstructor(constructorBuilder.build())
            .addProperties(properties)
            .addFunction(callOverride)
            .build()

        // RootScope extension — only exposes dataParams (not RootOptions, that's injected by call())
        val rootScopeExt = FunSpec.builder(fnName)
            .receiver(ROOT_SCOPE_CLASS)
            .returns(ROOT_CALLABLE_CLASS.parameterizedBy(returnTypeName))
            .apply {
                dataParams.forEach { param ->
                    addParameter(param.name!!.asString(), param.type.resolve().toTypeName())
                }
            }
            .addStatement(
                "return %L(%L)",
                className,
                dataParams.joinToString(", ") { it.name!!.asString() }
            )
            .build()

        val fileSpec = FileSpec.builder(pkg, className)
            .apply {
                if (companionClass != null) {
                    // Import the enclosing class so EnclosingClass.fnName() resolves
                    addImport(fn.packageName.asString(), companionClass.simpleName.asString())
                } else {
                    addImport(fn.packageName.asString(), fnName)
                }
            }
            .addType(classSpec)
            .addFunction(rootScopeExt)
            .build()

        val originatingFiles = listOfNotNull(fn.containingFile)
        fileSpec.writeTo(codeGenerator, aggregating = false, originatingKSFiles = originatingFiles)

        logger.info("Generated $pkg.$className from @RootFunction fun $fnName")
    }
}

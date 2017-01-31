/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.DeclarationContainerLoweringPass
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrConstructorImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFieldImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassOrAny
import org.jetbrains.kotlin.resolve.descriptorUtil.parents
import org.jetbrains.kotlin.resolve.descriptorUtil.parentsWithSelf
import org.jetbrains.kotlin.types.KotlinType
import java.util.*

class LocalDeclarationsLowering(val context: BackendContext) : DeclarationContainerLoweringPass {
    override fun lower(irDeclarationContainer: IrDeclarationContainer) {
        if (irDeclarationContainer is IrDeclaration &&
                irDeclarationContainer.descriptor.parents.any { it is CallableDescriptor }) {

            // Lowering of non-local declarations handles all local declarations inside.
            // This declaration is local and shouldn't be considered.
            return
        }

        // Continuous numbering across all declarations in the container.
        lambdasCount = 0

        irDeclarationContainer.declarations.transformFlat { memberDeclaration ->
            // TODO: may be do the opposite - specify the list of IR elements which need not to be transformed
            when (memberDeclaration) {
                is IrFunction -> LocalDeclarationsTransformer(memberDeclaration).lowerLocalDeclarations()
                is IrProperty -> LocalDeclarationsTransformer(memberDeclaration).lowerLocalDeclarations()
                is IrAnonymousInitializer -> LocalDeclarationsTransformer(memberDeclaration).lowerLocalDeclarations()
                else -> null
            }
        }
    }

    private var lambdasCount = 0

    private abstract class LocalContext {
        /**
         * @return the expression to get the value for given descriptor, or `null` if [IrGetValue] should be used.
         */
        abstract fun irGet(startOffset: Int, endOffset: Int, descriptor: ValueDescriptor): IrExpression?
    }

    private abstract class LocalContextWithClosureAsParameters : LocalContext() {

        abstract val declaration: IrFunction
        open val descriptor: FunctionDescriptor
            get() = declaration.descriptor

        abstract val transformedDescriptor: FunctionDescriptor

        val capturedValueToParameter: MutableMap<ValueDescriptor, ValueParameterDescriptor> = HashMap()

        override fun irGet(startOffset: Int, endOffset: Int, descriptor: ValueDescriptor): IrExpression? {
            val newDescriptor = capturedValueToParameter[descriptor] ?: return null

            return IrGetValueImpl(startOffset, endOffset, newDescriptor)
        }
    }

    private class LocalFunctionContext(override val declaration: IrFunction) : LocalContextWithClosureAsParameters() {
        lateinit var closure: Closure

        override lateinit var transformedDescriptor: FunctionDescriptor

        var index: Int = -1

        override fun toString(): String =
                "LocalFunctionContext for $descriptor"
    }

    private class LocalClassConstructorContext(override val declaration: IrConstructor) : LocalContextWithClosureAsParameters() {
        override val descriptor: ClassConstructorDescriptor
            get() = declaration.descriptor

        override lateinit var transformedDescriptor: ClassConstructorDescriptor

        override fun toString(): String =
                "LocalClassConstructorContext for $descriptor"
    }

    private class LocalClassContext(val declaration: IrClass) : LocalContext() {
        val descriptor: ClassDescriptor
            get() = declaration.descriptor

        lateinit var closure: Closure

        val capturedValueToField: MutableMap<ValueDescriptor, PropertyDescriptor> = HashMap()

        override fun irGet(startOffset: Int, endOffset: Int, descriptor: ValueDescriptor): IrExpression? {
            val fieldDescriptor = capturedValueToField[descriptor] ?: return null

            return IrGetFieldImpl(startOffset, endOffset, fieldDescriptor,
                    receiver = IrGetValueImpl(startOffset, endOffset, this.descriptor.thisAsReceiverParameter)
            )
        }

        override fun toString(): String =
                "LocalClassContext for ${descriptor}"
    }

    private inner class LocalDeclarationsTransformer(val memberDeclaration: IrDeclaration) {
        val localFunctions: MutableMap<FunctionDescriptor, LocalFunctionContext> = LinkedHashMap()
        val localClasses: MutableMap<ClassDescriptor, LocalClassContext> = LinkedHashMap()
        val localClassConstructors: MutableMap<ClassConstructorDescriptor, LocalClassConstructorContext> = LinkedHashMap()

        val transformedDescriptors = mutableMapOf<DeclarationDescriptor, DeclarationDescriptor>()

        val CallableDescriptor.transformed: CallableDescriptor?
            get() = transformedDescriptors[this] as CallableDescriptor?

        val oldParameterToNew: MutableMap<ParameterDescriptor, ParameterDescriptor> = HashMap()
        val newParameterToOld: MutableMap<ParameterDescriptor, ParameterDescriptor> = HashMap()
        val newParameterToCaptured: MutableMap<ValueParameterDescriptor, ValueDescriptor> = HashMap()

        fun lowerLocalDeclarations(): List<IrDeclaration>? {
            collectLocalDeclarations()
            if (localFunctions.isEmpty() && localClasses.isEmpty()) return null

            collectClosures()

            transformDescriptors()

            rewriteDeclarations()

            val result = collectRewrittenDeclarations()
            return result
        }

        private fun collectRewrittenDeclarations(): ArrayList<IrDeclaration> =
                ArrayList<IrDeclaration>(localFunctions.size + localClasses.size + 1).apply {
                    add(memberDeclaration)

                    localFunctions.values.mapTo(this) {
                        val original = it.declaration
                        IrFunctionImpl(
                                original.startOffset, original.endOffset, original.origin,
                                it.transformedDescriptor,
                                original.body
                        )
                    }

                    localClasses.values.mapTo(this) {
                        it.declaration
                    }
                }

        private inner class FunctionBodiesRewriter(val localContext: LocalContext?) : IrElementTransformerVoid() {

            override fun visitClass(declaration: IrClass): IrStatement {
                if (declaration.descriptor in localClasses) {
                    // Replace local class definition with an empty composite.
                    return IrCompositeImpl(declaration.startOffset, declaration.endOffset, context.builtIns.unitType)
                } else {
                    return super.visitClass(declaration)
                }
            }

            override fun visitFunction(declaration: IrFunction): IrStatement {
                if (declaration.descriptor in localFunctions) {
                    // Replace local function definition with an empty composite.
                    return IrCompositeImpl(declaration.startOffset, declaration.endOffset, context.builtIns.unitType)
                } else {
                    return super.visitFunction(declaration)
                }
            }

            override fun visitConstructor(declaration: IrConstructor): IrStatement {
                // Body is transformed separately.

                val transformedDescriptor = localClassConstructors[declaration.descriptor]?.transformedDescriptor
                if (transformedDescriptor != null) {
                    return IrConstructorImpl(declaration.startOffset, declaration.endOffset, declaration.origin,
                            transformedDescriptor, declaration.body!!)
                } else {
                    return super.visitConstructor(declaration)
                }
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val descriptor = expression.descriptor

                localContext?.irGet(expression.startOffset, expression.endOffset, descriptor)?.let {
                    return it
                }

                oldParameterToNew[descriptor]?.let {
                    return IrGetValueImpl(expression.startOffset, expression.endOffset, it)
                }

                return expression
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val oldCallee = expression.descriptor.original
                val newCallee = oldCallee.transformed ?: return expression

                val newCall = createNewCall(expression, newCallee).fillArguments(expression)

                return newCall
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                val oldCallee = expression.descriptor.original
                val newCallee = transformedDescriptors[oldCallee] as ClassConstructorDescriptor? ?: return expression

                val newExpression = IrDelegatingConstructorCallImpl(
                        expression.startOffset, expression.endOffset,
                        newCallee,
                        remapTypeArguments(expression, newCallee)
                ).fillArguments(expression)

                return newExpression
            }

            private fun <T : IrMemberAccessExpression> T.fillArguments(oldExpression: IrMemberAccessExpression): T {

                mapValueParameters { newValueParameterDescriptor ->
                    val oldParameter = newParameterToOld[newValueParameterDescriptor]

                    if (oldParameter != null) {
                        oldExpression.getValueArgument(oldParameter as ValueParameterDescriptor)
                    } else {
                        // The callee expects captured value as argument.
                        val capturedValueDescriptor =
                                newParameterToCaptured[newValueParameterDescriptor] ?:
                                        throw AssertionError("Non-mapped parameter $newValueParameterDescriptor")

                        localContext?.irGet(
                                oldExpression.startOffset, oldExpression.endOffset,
                                capturedValueDescriptor
                        ) ?:
                                // Captured value is directly available for the caller.
                                IrGetValueImpl(oldExpression.startOffset, oldExpression.endOffset, capturedValueDescriptor)
                    }

                }

                dispatchReceiver = oldExpression.dispatchReceiver
                extensionReceiver = oldExpression.extensionReceiver

                return this
            }

            override fun visitCallableReference(expression: IrCallableReference): IrExpression {
                expression.transformChildrenVoid(this)

                val oldCallee = expression.descriptor.original
                val newCallee = oldCallee.transformed ?: return expression

                val newCallableReference = IrCallableReferenceImpl(
                        expression.startOffset, expression.endOffset,
                        expression.type, // TODO functional type for transformed descriptor
                        newCallee,
                        remapTypeArguments(expression, newCallee),
                        expression.origin
                ).fillArguments(expression)

                return newCallableReference
            }

            override fun visitReturn(expression: IrReturn): IrExpression {
                expression.transformChildrenVoid(this)

                val oldReturnTarget = expression.returnTarget
                val newReturnTarget = oldReturnTarget.transformed ?: return expression

                return IrReturnImpl(expression.startOffset, expression.endOffset, newReturnTarget, expression.value)
            }

            override fun visitDeclarationReference(expression: IrDeclarationReference): IrExpression {
                if (expression.descriptor in transformedDescriptors) {
                    TODO()
                }
                return super.visitDeclarationReference(expression)
            }

            override fun visitDeclaration(declaration: IrDeclaration): IrStatement {
                if (declaration.descriptor in transformedDescriptors) {
                    TODO()
                }
                return super.visitDeclaration(declaration)
            }
        }

        private fun rewriteFunctionBody(irDeclaration: IrDeclaration, localContext: LocalContext?) {
            irDeclaration.transformChildrenVoid(FunctionBodiesRewriter(localContext))
        }

        private object DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE :
                IrDeclarationOriginImpl("FIELD_FOR_CAPTURED_VALUE") {}

        private fun rewriteClassMembers(irClass: IrClass, localClassContext: LocalClassContext) {
            irClass.transformChildrenVoid(FunctionBodiesRewriter(localClassContext))

            val primaryConstructor = irClass.descriptor.unsubstitutedPrimaryConstructor ?:
                    TODO("local classes without primary constructor")

            val primaryConstructorContext = localClassConstructors[primaryConstructor]!!

            localClassContext.capturedValueToField.forEach { capturedValue, fieldDescriptor ->

                val capturedValueExpression =
                        primaryConstructorContext.irGet(irClass.startOffset, irClass.endOffset, capturedValue)!!

                irClass.declarations.add(
                        IrFieldImpl(
                                irClass.startOffset, irClass.endOffset,
                                DECLARATION_ORIGIN_FIELD_FOR_CAPTURED_VALUE,
                                fieldDescriptor,
                                IrExpressionBodyImpl(
                                        irClass.startOffset, irClass.endOffset,
                                        capturedValueExpression
                                )
                        )
                )
            }
        }

        private fun rewriteDeclarations() {
            localFunctions.values.forEach {
                rewriteFunctionBody(it.declaration, it)
            }

            localClassConstructors.values.forEach {
                rewriteFunctionBody(it.declaration, it)
            }

            localClasses.values.forEach {
                rewriteClassMembers(it.declaration, it)
            }

            rewriteFunctionBody(memberDeclaration, null)
        }

        private fun createNewCall(oldCall: IrCall, newCallee: CallableDescriptor) =
                if (oldCall is IrCallWithShallowCopy)
                    oldCall.shallowCopy(oldCall.origin, newCallee, oldCall.superQualifier)
                else
                    IrCallImpl(
                            oldCall.startOffset, oldCall.endOffset,
                            newCallee,
                            remapTypeArguments(oldCall, newCallee),
                            oldCall.origin, oldCall.superQualifier
                    )

        private fun remapTypeArguments(oldExpression: IrMemberAccessExpression, newCallee: CallableDescriptor): Map<TypeParameterDescriptor, KotlinType>? {
            val oldCallee = oldExpression.descriptor

            return if (oldCallee.typeParameters.isEmpty())
                null
            else oldCallee.typeParameters.associateBy(
                    { newCallee.typeParameters[it.index] },
                    { oldExpression.getTypeArgument(it)!! }
            )
        }

        private fun transformDescriptors() {
            localFunctions.values.forEach {
                createLiftedDescriptor(it)
            }

            localClasses.values.forEach {
                createFieldsForCapturedValues(it)
            }

            localClassConstructors.values.forEach {
                createTransformedConstructorDescriptor(it)
            }
        }

        private fun suggestLocalName(descriptor: DeclarationDescriptor): String {
            localFunctions[descriptor]?.let {
                if (it.index >= 0)
                    return "lambda-${it.index}"
            }

            return descriptor.name.asString()
        }

        private fun generateNameForLiftedDeclaration(descriptor: DeclarationDescriptor,
                                                     newOwner: DeclarationDescriptor): Name =
                Name.identifier(
                        descriptor.parentsWithSelf
                                .takeWhile { it != newOwner }
                                .toList().reversed()
                                .map { suggestLocalName(it) }
                                .joinToString(separator = "$")
                )

        private fun createLiftedDescriptor(localFunctionContext: LocalFunctionContext) {
            val oldDescriptor = localFunctionContext.descriptor

            val memberOwner = memberDeclaration.descriptor.containingDeclaration!!
            val newDescriptor = SimpleFunctionDescriptorImpl.create(
                    memberOwner,
                    oldDescriptor.annotations,
                    generateNameForLiftedDeclaration(oldDescriptor, memberOwner),
                    CallableMemberDescriptor.Kind.SYNTHESIZED,
                    oldDescriptor.source
            )

            localFunctionContext.transformedDescriptor = newDescriptor
            transformedDescriptors[oldDescriptor] = newDescriptor

            if (oldDescriptor.dispatchReceiverParameter != null) {
                throw AssertionError("local functions must not have dispatch receiver")
            }

            val newDispatchReceiverParameter = null

            // Do not substitute type parameters for now.
            val newTypeParameters = oldDescriptor.typeParameters

            // TODO: consider using fields to access the closure of enclosing class.
            val capturedValues = localFunctionContext.closure.capturedValues

            val newValueParameters = createTransformedValueParameters(localFunctionContext, capturedValues)

            newDescriptor.initialize(
                    oldDescriptor.extensionReceiverParameter?.type,
                    newDispatchReceiverParameter,
                    newTypeParameters,
                    newValueParameters,
                    oldDescriptor.returnType,
                    Modality.FINAL,
                    Visibilities.PRIVATE
            )

            oldDescriptor.extensionReceiverParameter?.let {
                recordRemappedParameter(it, newDescriptor.extensionReceiverParameter!!)
            }
        }

        private fun createTransformedValueParameters(localContext: LocalContextWithClosureAsParameters,
                                                     capturedValues: List<ValueDescriptor>
        ): List<ValueParameterDescriptor> {

            val oldDescriptor = localContext.descriptor
            val newDescriptor = localContext.transformedDescriptor

            val closureParametersCount = capturedValues.size
            val newValueParametersCount = closureParametersCount + oldDescriptor.valueParameters.size

            val newValueParameters = ArrayList<ValueParameterDescriptor>(newValueParametersCount).apply {
                capturedValues.mapIndexedTo(this) { i, capturedValueDescriptor ->
                    createUnsubstitutedCapturedValueParameter(newDescriptor, capturedValueDescriptor, i).apply {
                        localContext.recordCapturedAsParameter(capturedValueDescriptor, this)
                    }
                }

                oldDescriptor.valueParameters.mapIndexedTo(this) { i, oldValueParameterDescriptor ->
                    createUnsubstitutedParameter(newDescriptor, oldValueParameterDescriptor, closureParametersCount + i).apply {
                        recordRemappedParameter(oldValueParameterDescriptor, this)
                    }
                }
            }
            return newValueParameters
        }

        private fun createTransformedConstructorDescriptor(localFunctionContext: LocalClassConstructorContext) {
            val oldDescriptor = localFunctionContext.descriptor
            val localClassContext = localClasses[oldDescriptor.containingDeclaration]!!
            val newDescriptor = ClassConstructorDescriptorImpl.create(
                    localClassContext.descriptor,
                    Annotations.EMPTY, oldDescriptor.isPrimary, oldDescriptor.source)

            localFunctionContext.transformedDescriptor = newDescriptor
            transformedDescriptors[oldDescriptor] = newDescriptor

            // Do not substitute type parameters for now.
            val newTypeParameters = oldDescriptor.typeParameters

            val capturedValues = mutableListOf<ValueDescriptor>()
            var classDescriptor = oldDescriptor.containingDeclaration
            while (true) {
                // Capture all values from the hierarchy since we need to call constructor of super class
                // with his captured values.
                val context = localClasses[classDescriptor] ?: break
                capturedValues.addAll(context.closure.capturedValues)
                classDescriptor = classDescriptor.getSuperClassOrAny()
            }

            val newValueParameters = createTransformedValueParameters(localFunctionContext, capturedValues)

            newDescriptor.initialize(
                    newValueParameters,
                    Visibilities.PRIVATE,
                    newTypeParameters
            )

            oldDescriptor.dispatchReceiverParameter?.let {
                recordRemappedParameter(it, newDescriptor.dispatchReceiverParameter!!)
            }

            oldDescriptor.extensionReceiverParameter?.let {
                throw AssertionError("constructors can't have extension receiver")
            }
        }

        private fun createFieldsForCapturedValues(localClassContext: LocalClassContext) {
            val classDescriptor = localClassContext.descriptor

            localClassContext.closure.capturedValues.forEach { capturedValue ->
                val fieldDescriptor = PropertyDescriptorImpl.create(
                        classDescriptor,
                        Annotations.EMPTY,
                        Modality.FINAL,
                        Visibilities.PRIVATE,
                        /* isVar = */ false,
                        suggestNameForCapturedValue(capturedValue),
                        CallableMemberDescriptor.Kind.SYNTHESIZED,
                        SourceElement.NO_SOURCE,
                        /* lateInit = */ false,
                        /* isConst = */ false,
                        /* isHeader = */ false,
                        /* isImpl = */ false,
                        /* isExternal = */ false,
                        /* isDelegated = */ false)

                fieldDescriptor.initialize(/* getter = */ null, /* setter = */ null)

                val extensionReceiverParameter: ReceiverParameterDescriptor? = null

                fieldDescriptor.setType(
                        capturedValue.type,
                        emptyList<TypeParameterDescriptor>(),
                        classDescriptor.thisAsReceiverParameter,
                        extensionReceiverParameter)

                localClassContext.capturedValueToField[capturedValue] = fieldDescriptor
            }
        }

        private fun LocalContextWithClosureAsParameters.recordCapturedAsParameter(
                oldDescriptor: ValueDescriptor,
                newDescriptor: ValueParameterDescriptor
        ) {

            capturedValueToParameter[oldDescriptor] = newDescriptor
            newParameterToCaptured[newDescriptor] = oldDescriptor

        }

        private fun <K, V> MutableMap<K, V>.putAbsentOrSame(key: K, value: V) {
            val current = this.getOrPut(key, { value })

            if (current != value) {
                error("$current != $value")
            }
        }

        private fun recordRemappedParameter(oldDescriptor: ParameterDescriptor, newDescriptor: ParameterDescriptor) {
            oldParameterToNew.putAbsentOrSame(oldDescriptor, newDescriptor)
            newParameterToOld.putAbsentOrSame(newDescriptor, oldDescriptor)
        }

        private fun suggestNameForCapturedValue(valueDescriptor: ValueDescriptor): Name =
                if (valueDescriptor.name.isSpecial) {
                    val oldNameStr = valueDescriptor.name.asString()
                    Name.identifier("$" + oldNameStr.substring(1, oldNameStr.length - 1))
                } else
                    valueDescriptor.name

        private fun createUnsubstitutedCapturedValueParameter(
                newParameterOwner: CallableMemberDescriptor,
                valueDescriptor: ValueDescriptor,
                index: Int
        ): ValueParameterDescriptor =
                ValueParameterDescriptorImpl(
                        newParameterOwner, null, index,
                        valueDescriptor.annotations,
                        suggestNameForCapturedValue(valueDescriptor),
                        valueDescriptor.type,
                        false, false, false, null, valueDescriptor.source
                )

        private fun createUnsubstitutedParameter(
                newParameterOwner: CallableMemberDescriptor,
                valueParameterDescriptor: ValueParameterDescriptor,
                newIndex: Int
        ): ValueParameterDescriptor =
                valueParameterDescriptor.copy(newParameterOwner, valueParameterDescriptor.name, newIndex)


        private fun collectClosures() {
            memberDeclaration.acceptChildrenVoid(object : AbstractClosureAnnotator() {
                override fun recordFunctionClosure(functionDescriptor: FunctionDescriptor, closure: Closure) {
                    localFunctions[functionDescriptor]?.closure = closure
                }

                override fun recordClassClosure(classDescriptor: ClassDescriptor, closure: Closure) {
                    localClasses[classDescriptor]?.closure = closure
                }
            })
        }

        private fun collectLocalDeclarations() {
            memberDeclaration.acceptChildrenVoid(object : IrElementVisitorVoid {

                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                private fun DeclarationDescriptor.declaredInFunction() = when (this.containingDeclaration) {
                    is CallableDescriptor -> true
                    is ClassDescriptor -> false
                    is PackageFragmentDescriptor -> false
                    else -> TODO(this.toString() + "\n" + this.containingDeclaration.toString())
                }

                override fun visitFunction(declaration: IrFunction) {
                    declaration.acceptChildrenVoid(this)

                    val descriptor = declaration.descriptor

                    if (descriptor.declaredInFunction()) {
                        val localFunctionContext = LocalFunctionContext(declaration)

                        localFunctions[descriptor] = localFunctionContext

                        if (descriptor.name.isSpecial) {
                            localFunctionContext.index = lambdasCount++
                        }
                    }

                }

                override fun visitConstructor(declaration: IrConstructor) {
                    declaration.acceptChildrenVoid(this)

                    val descriptor = declaration.descriptor
                    assert(!descriptor.declaredInFunction())

                    if (descriptor.constructedClass.isInner) return

                    localClassConstructors[descriptor] = LocalClassConstructorContext(declaration)
                }

                override fun visitClass(declaration: IrClass) {
                    declaration.acceptChildrenVoid(this)

                    val descriptor = declaration.descriptor

                    if (descriptor.isInner) return

                    // Local nested classes can only be inner.
                    assert(descriptor.declaredInFunction())

                    val localClassContext = LocalClassContext(declaration)
                    localClasses[descriptor] = localClassContext
                }
            })
        }
    }

}
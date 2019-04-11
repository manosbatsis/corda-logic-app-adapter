package com.r3.logicapps.processing

import com.r3.logicapps.BusRequest
import com.r3.logicapps.BusResponse
import com.r3.logicapps.Invocable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic

open class MessageProcessorImpl(
    private val startFlowDelegate: (FlowLogic<*>) -> FlowInvocationResult
) : MessageProcessor {
    override fun invoke(message: BusRequest): BusResponse = when (message) {
        is BusRequest.InvokeFlowWithoutInputStates ->
            processInvocationMessage(message.requestId, null, message, true)
        is BusRequest.InvokeFlowWithInputStates ->
            processInvocationMessage(message.requestId, message.linearId, message, false)
        is BusRequest.QueryFlowState ->
            processQueryMessage(message.requestId, message.linearId)
    }

    private fun processInvocationMessage(
        requestId: String,
        linearId: UniqueIdentifier?,
        invocable: Invocable,
        isNew: Boolean
    ): BusResponse {
        return try {
            val flowLogic = deriveFlowLogic(invocable.workflowName, invocable.parameters)
            val result = startFlowDelegate(flowLogic)
            BusResponse.FlowOutput(
                ingressType = invocable::class,
                requestId = requestId,
                linearId = result.linearId ?: linearId ?: error("Unable to derive linear ID after flow invocation"),
                fields = result.fields,
                isNewContract = isNew
            )
        } catch (exception: Throwable) {
            BusResponse.FlowError(invocable::class, requestId, linearId, exception)
        }
    }

    private fun processQueryMessage(requestId: String, linearId: UniqueIdentifier?): BusResponse {
        TODO("Handler for QueryFlowState not implemented")
    }

    private fun deriveFlowLogic(flowName: String, parameters: Map<String, String>): FlowLogic<*> {
        val clazz = try {
            Class.forName(flowName)
        } catch (_: ClassNotFoundException) {
            throw ClassNotFoundException("Unable to find '$flowName' on the class path")
        }
        val flowLogic = clazz.asSubclass(FlowLogic::class.java) ?: error("$flowName is not a subclass of FlowLogic")
        val statement = FlowInvoker.getFlowStatementFromString(flowLogic, parameters)
        if (statement.errors.isNotEmpty()) {
            throw IllegalStateException(statement.errors.joinToString("; "))
        }
        val ctor = statement.ctor ?: error("Unable to derive applicable constructor for $flowName")
        val arguments = statement.arguments ?: error("Unable to derive arguments for $flowName")
        return ctor.newInstance(*arguments) as? FlowLogic<*>
            ?: error("Unable to instantiate $flowName with provided arguments")
    }
}
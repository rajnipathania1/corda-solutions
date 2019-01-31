package com.r3.businessnetworks.billing.flows.member

import co.paralleluniverse.fibers.Suspendable
import com.r3.businessnetworks.billing.states.BillingChipState
import com.r3.businessnetworks.billing.states.BillingContract
import com.r3.businessnetworks.billing.states.BillingState
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
class AttachUnspentChipsFlow(private val billingState : StateAndRef<BillingState>) : FlowLogic<Pair<BillingState, SignedTransaction>?>() {
    @Suspendable
    override fun call() : Pair<BillingState, SignedTransaction>? {
        // fetching all unspent chips from vault
        val unspentChips
                = serviceHub.vaultService.queryBy<BillingChipState>().states
                .filter { it.state.data.billingStateLinearId == billingState.state.data.linearId}

        return if (!unspentChips.isEmpty()) {
            val notary = serviceHub.networkMapCache.notaryIdentities.single()
            val builder = TransactionBuilder(notary)
                    .addInputState(billingState)
                    .addCommand(BillingContract.Commands.AttachBack(), ourIdentity.owningKey)
            var totalUnspent = 0L
            unspentChips.forEach {
                builder.addInputState(it)
                totalUnspent += it.state.data.amount
            }
            val outputBillingState = billingState.state.data.copy(spent = billingState.state.data.spent - totalUnspent)
            builder.addOutputState(outputBillingState, BillingContract.CONTRACT_NAME)

            builder.verify(serviceHub)

            val stx = serviceHub.signInitialTransaction(builder)

            val sessionWithIssuer = initiateFlow(billingState.state.data.issuer)

            Pair(outputBillingState, subFlow(FinalityFlow(stx, listOf(sessionWithIssuer))))
        } else null
    }
}
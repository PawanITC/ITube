package ITubeBackend.service.payments;

import ITubeBackend.config.PaymentProperties;
import ITubeBackend.config.StripeProperties;
import ITubeBackend.entities.ChannelMembershipEntity;
import ITubeBackend.repository.ChannelMembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Safety net for missed webhooks: memberships still marked as entitled after their paid period
 * (plus grace) are re-read from Stripe, which either moves the period on or expires them.
 */
@Component
public class MembershipReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(MembershipReconciliationJob.class);

    private final ChannelMembershipRepository membershipRepository;
    private final MembershipSyncService membershipSync;
    private final TransactionTemplate transactionTemplate;
    private final PaymentProperties paymentProperties;
    private final StripeProperties stripeProperties;
    private final Clock clock;

    public MembershipReconciliationJob(ChannelMembershipRepository membershipRepository,
                                       MembershipSyncService membershipSync, TransactionTemplate transactionTemplate,
                                       PaymentProperties paymentProperties, StripeProperties stripeProperties,
                                       Clock clock) {
        this.membershipRepository = membershipRepository;
        this.membershipSync = membershipSync;
        this.transactionTemplate = transactionTemplate;
        this.paymentProperties = paymentProperties;
        this.stripeProperties = stripeProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${itube.memberships.reconcile-interval:PT15M}", initialDelayString = "PT1M")
    public void reconcile() {
        if (!stripeProperties.configured()) return;
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(paymentProperties.memberships().accessGrace());
        for (ChannelMembershipEntity membership : membershipRepository.findEntitledEndedBefore(cutoff)) {
            try {
                transactionTemplate.executeWithoutResult(status -> membershipSync.sync(membership.getStripeSubscriptionId()));
            } catch (Exception exception) {
                log.warn("Reconciling membership {} failed: {}", membership.getMembershipId(), exception.toString());
            }
        }
    }
}

package site.asm0dey.stillpress.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.publish.PublishStatus;

@ExtendWith(MockitoExtension.class)
class PublishResourceTest {
    private static final PublishStatus.State IDLE = new PublishStatus.State("idle", 0, 0, null, null);
    @Mock
    PublishStatus status;
    @Mock
    PublishQueue queue;
    @Mock
    Jobs jobs;

    @Test
    void stateIsThePublishersCurrentState() {
        when(status.current()).thenReturn(IDLE);
        assertThat(new PublishResource(status, queue, jobs).state()).isEqualTo(IDLE);
    }

    @Test
    void republishQueuesEverythingThenRequestsAPublishAndAnswers202WithTheState() {
        when(status.current()).thenReturn(IDLE);
        try (var r = new PublishResource(status, queue, jobs).republish()) {
            assertThat(r.getStatus()).isEqualTo(202);
            assertThat(r.getEntity()).isEqualTo(IDLE);
        }
        var order = inOrder(queue, jobs);
        order.verify(queue).all();
        order.verify(jobs).requestPublish();
    }
}

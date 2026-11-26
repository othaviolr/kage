package com.kage.shared;

import com.kage.AbstractIntegrationTest;
import com.kage.shared.domain.DomainTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Na aplicação de verdade, o "agora" das entidades é o mesmo do bean Clock. */
class ClockBindingIntegrationTest extends AbstractIntegrationTest {

    @Autowired Clock clock;

    @Test
    void domainTime_seguiOBeanClock() {
        assertThat(DomainTime.clock().getZone()).isEqualTo(clock.getZone());
        assertThat(Duration.between(clock.instant(), DomainTime.instant()).abs()).isLessThan(Duration.ofSeconds(1));
    }
}

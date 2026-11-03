package ITubeBackend.repository;

import ITubeBackend.entities.MembershipPerkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;

public interface MembershipPerkRepository extends JpaRepository<MembershipPerkEntity, Long> {
    List<MembershipPerkEntity> findByTierIdIn(Collection<Long> tierIds);

    @Modifying
    void deleteByTierId(Long tierId);
}

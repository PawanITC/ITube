package ITubeBackend.repository;

import ITubeBackend.entities.ExternalIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentityEntity, ExternalIdentityEntity.Key> {
}

package ITubeBackend.repository;

import ITubeBackend.entities.AccountEntity;
import ITubeBackend.dto.accounts.AccountDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    Optional<AccountEntity> findEntityByEmail(String email);

    @Query(value = "SELECT user_id, firstname, lastname, email FROM accounts WHERE email = :email",
            nativeQuery = true)
    Optional<AccountDto> findDtoByEmail(@Param("email") String email);

    @Query(value = "SELECT user_id, firstname, lastname, email FROM accounts WHERE user_id = :id",
            nativeQuery = true)
    AccountDto findDtoById(@Param("id") Long id);

}

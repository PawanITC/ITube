package ITubeBackend.repository;

import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.dto.ChannelDto;
import ITubeBackend.dto.ChannelSearchDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface ChannelRepository extends JpaRepository<ChannelEntity, Long> {
    @Query(value = "SELECT channel_id, user_id, channel_name FROM channel " +
            "WHERE user_id = :userId AND channel_name = :channelName", nativeQuery = true)
    Optional<ChannelDto> findDtoByOwnerAndName(@Param("userId") Long userId,
                                               @Param("channelName") String channelName);

    @Query(value = "SELECT channel_id, user_id, channel_name FROM channel WHERE user_id = :userId ORDER BY channel_id",
            nativeQuery = true)
    List<ChannelDto> findDtosByUserId(@Param("userId") Long userId);

    @Query("select new ITubeBackend.dto.ChannelSearchDto(c.channelId, c.channelName) " +
            "from ChannelEntity c where lower(c.channelName) like lower(concat('%', :query, '%')) " +
            "and (:currentUserId is null or c.user.userId <> :currentUserId) " +
            "order by c.channelName")
    List<ChannelSearchDto> searchByChannelName(@Param("query") String query,
                                               @Param("currentUserId") Long currentUserId);

    @Query("select c.user.userId from ChannelEntity c where c.channelId = :channelId")
    Optional<Long> findOwnerUserIdByChannelId(@Param("channelId") Long channelId);

    @Query(value = "SELECT c.channel_id, c.user_id, c.channel_name FROM channel c " +
            "JOIN subscriptions s ON s.channel_id = c.channel_id " +
            "WHERE s.user_id = :userId ORDER BY c.channel_name", nativeQuery = true)
    List<ChannelDto> findSubscribedChannelsByUserId(@Param("userId") Long userId);
}

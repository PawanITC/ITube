package ITubeBackend.repository;

import ITubeBackend.dto.comments.CommentDto;
import ITubeBackend.entities.CommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<CommentEntity, Long> {
    @Query("select new ITubeBackend.dto.comments.CommentDto(c.commentId, c.content, c.createdAt, concat(concat(c.user.firstname, ' '), c.user.lastname)) " +
            "from CommentEntity c where c.video.videoId = :videoId order by c.createdAt desc, c.commentId desc")
    List<CommentDto> findDtosByVideoId(@Param("videoId") Long videoId);
}

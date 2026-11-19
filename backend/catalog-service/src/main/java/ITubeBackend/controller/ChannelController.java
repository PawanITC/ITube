package ITubeBackend.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.channel.ChannelDetailsDto;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.channel.ChannelSearchDto;
import ITubeBackend.dto.subscribtions.SubscriptionStatusDto;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.channel.ChannelService;
import ITubeBackend.service.channel.SubscriptionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Tag(name = "Channels", description = "Channel pages, search and free Subscribe (never involves payment)")
@RestController
@RequestMapping("/api/channels")
public class ChannelController {
    private final ChannelService channelService;
    private final SubscriptionService subscriptionService;

    public ChannelController(ChannelService channelService, SubscriptionService subscriptionService) {
        this.channelService = channelService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/search")
    public List<ChannelSearchDto> searchChannels(@RequestParam(defaultValue = "") String q, HttpSession session) {
        String query = q.trim();
        if (query.isEmpty()) return List.of();
        Object userId = session.getAttribute("userId");
        Long currentUserId = userId instanceof Long id ? id : null;
        return channelService.search(query, currentUserId);
    }

    @GetMapping("/subscriptions")
    public List<ChannelDto> getSubscribedChannels(HttpSession session) {
        return channelService.subscribedChannels(requireUserId(session));
    }

    /** The signed-in user's channels (moved here from the old dev-session controller: channels are catalog data). */
    @GetMapping("/mine")
    public List<ChannelDto> getMyChannels(HttpSession session) {
        return channelService.channelsOwnedBy(CurrentUser.require(session));
    }

    @GetMapping("/{channelId}")
    public ChannelDetailsDto getChannel(@PathVariable Long channelId, HttpSession session) {
        Object userId = session.getAttribute("userId");
        Long currentUserId = userId instanceof Long id ? id : null;
        return channelService.getChannelDetails(channelId, currentUserId);
    }

    @PostMapping("/{channelId}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionStatusDto subscribe(@PathVariable Long channelId, HttpSession session) {
        return subscriptionService.subscribe(requireUserId(session), channelId);
    }

    @DeleteMapping("/{channelId}/subscriptions")
    public SubscriptionStatusDto unsubscribe(@PathVariable Long channelId, HttpSession session) {
        return subscriptionService.unsubscribe(requireUserId(session), channelId);
    }

    private Long requireUserId(HttpSession session) {
        Object userId = session.getAttribute("userId");
        if (!(userId instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session before subscribing");
        }
        return id;
    }
}

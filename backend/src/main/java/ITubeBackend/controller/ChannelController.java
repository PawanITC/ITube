package ITubeBackend.controller;

import ITubeBackend.dto.ChannelDetailsDto;
import ITubeBackend.dto.ChannelDto;
import ITubeBackend.dto.ChannelSearchDto;
import ITubeBackend.dto.SubscriptionStatusDto;
import ITubeBackend.repository.ChannelRepository;
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

@RestController
@RequestMapping("/api/channels")
public class ChannelController {
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final SubscriptionService subscriptionService;

    public ChannelController(ChannelRepository channelRepository, ChannelService channelService,
                             SubscriptionService subscriptionService) {
        this.channelRepository = channelRepository;
        this.channelService = channelService;
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/search")
    public List<ChannelSearchDto> searchChannels(@RequestParam(defaultValue = "") String q, HttpSession session) {
        String query = q.trim();
        if (query.isEmpty()) return List.of();
        Object userId = session.getAttribute("userId");
        Long currentUserId = userId instanceof Long id ? id : null;
        return channelRepository.searchByChannelName(query, currentUserId);
    }

    @GetMapping("/subscriptions")
    public List<ChannelDto> getSubscribedChannels(HttpSession session) {
        return channelRepository.findSubscribedChannelsByUserId(requireUserId(session));
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

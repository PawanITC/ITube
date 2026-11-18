Feature: Caching and resilience in the catalog
  Hot data (who may watch a video, the home feed) is cached in memory with Caffeine. Changes must still show up
  straight away, and an outage in another service must only affect the features that really need it.

  Background:
    Given the catalog runs on 2 database shards
    And a creator "Hedy" whose data lives on shard 1
    And a viewer "Viewer"

  Scenario: Making a video private takes effect at once despite caching
    When "Hedy" uploads a public video "Frequency hopping"
    And the feed cache has expired
    And a visitor opens the home feed
    Then the home feed shows "Frequency hopping"
    When "Hedy" makes "Frequency hopping" private
    Then the home feed does not show "Frequency hopping"
    And "Viewer" gets "not found" when asking to play "Frequency hopping"

  Scenario: Public videos keep playing while payments-service is down
    Given payments-service is down
    When "Hedy" uploads a public video "Spread spectrum"
    Then "Viewer" may play "Spread spectrum"

  Scenario: Members-only videos fail safe while payments-service is down
    Given payments-service is down
    When "Hedy" uploads a members-only video "Torpedo guidance"
    Then "Viewer" is told to try again later when asking to play "Torpedo guidance"
    But "Hedy" may still play "Torpedo guidance"

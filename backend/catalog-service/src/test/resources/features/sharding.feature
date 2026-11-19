Feature: Sharded video catalog
  Creators are spread over several databases ("shards"). A creator's channel and videos always live together on
  one shard, and every channel and video id says which shard it lives on, so one video can be found without asking
  every database. Pages that need everybody's videos (the home feed, search) ask all shards at once and merge.

  These scenarios run against two real (in-memory) databases.

  Background:
    Given the catalog runs on 2 database shards

  Scenario: A creator's channel and videos live together on one shard
    Given a creator "Ada" whose data lives on shard 1
    When "Ada" uploads a public video "Analytical engines"
    Then the video "Analytical engines" is stored only on shard 1
    And the video "Analytical engines" carries the same shard in its id as the channel of "Ada"

  Scenario: The home feed merges the newest videos from every shard
    Given a creator "Grace" whose data lives on shard 0
    And a creator "Linus" whose data lives on shard 1
    When "Grace" uploads a public video "Compilers"
    And "Linus" uploads a public video "Kernels"
    And the feed cache has expired
    Then the home feed shows "Kernels" before "Compilers"

  Scenario: Channel search finds channels on every shard
    Given a creator "Search Alpha" whose data lives on shard 0
    And a creator "Search Beta" whose data lives on shard 1
    When a visitor searches channels for "Search"
    Then the results include the channels of "Search Alpha" and "Search Beta"

  Scenario: A viewer's subscriptions are gathered from every shard
    Given a creator "Margaret" whose data lives on shard 0
    And a creator "Barbara" whose data lives on shard 1
    And a viewer "Viewer"
    When "Viewer" subscribes to the channels of "Margaret" and "Barbara"
    Then the subscriptions of "Viewer" list the channels of "Margaret" and "Barbara"

  Scenario: Other services can look up videos from several shards in one call
    Given a creator "Edsger" whose data lives on shard 0
    And a creator "Tony" whose data lives on shard 1
    When "Edsger" uploads a public video "Go to statement"
    And "Tony" uploads a public video "Quicksort"
    Then one internal summaries call returns both "Go to statement" and "Quicksort"

  Scenario: Ids created before sharding keep working
    Then an id created before sharding, such as 12345, belongs to shard 0
    And the largest possible id is still exact in a browser

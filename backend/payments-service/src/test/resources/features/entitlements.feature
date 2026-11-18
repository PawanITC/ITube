Feature: Who may watch members-only videos
  payments-service knows who is a member of which channel. catalog-service asks it (internal API) before letting
  anyone play a members-only video. Membership pages show channel names from catalog-service, but must not break
  when the catalog is down.

  Background:
    Given channel 5 has a membership tier at level 1 and one at level 3

  Scenario Outline: Deciding access to a members-only video
    Given viewer 7 is a "<status>" member at level <member level> whose period ends in <days> days
    When the catalog asks whether viewer 7 may play a members-only video of channel 5 that needs level <needed>
    Then the answer is "<answer>"

    Examples:
      | status   | member level | days | needed | answer               |
      | active   | 1            | 20   | 1      | allowed              |
      | active   | 1            | 20   | 3      | HIGHER_TIER_REQUIRED |
      | active   | 3            | 20   | 3      | allowed              |
      | active   | 1            | -3   | 1      | MEMBERSHIP_REQUIRED  |
      | canceled | 1            | 20   | 1      | MEMBERSHIP_REQUIRED  |

  Scenario: A viewer who never joined needs a membership
    When the catalog asks whether viewer 8 may play a members-only video of channel 5 that needs level 1
    Then the answer is "MEMBERSHIP_REQUIRED"

  Scenario: The memberships page still loads while the catalog is down
    Given viewer 9 is a "active" member at level 1 whose period ends in 20 days
    And catalog-service is down
    When viewer 9 opens their memberships page
    Then the page lists 1 membership with the placeholder name "Channel 5"

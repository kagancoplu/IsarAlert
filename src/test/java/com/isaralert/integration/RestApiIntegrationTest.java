package com.isaralert.integration;

import com.isaralert.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API over HTTP (MockMvc) against the real database: happy paths, validation,
 * and the error responses clients get for bad input.
 */
@DisplayName("REST API")
class RestApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Creates criteria via the API and returns the new criteria's id. */
    private long createCriteria(long chatId, String extraFields) throws Exception {
        String body = postJson("/api/criteria", "{\"telegramChatId\": " + chatId + extraFields + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long userIdOf(long chatId) {
        return jdbc.queryForObject("SELECT id FROM users WHERE telegram_chat_id = ?", Long.class, chatId);
    }

    private long insertListing(String externalId) {
        return jdbc.queryForObject("""
                INSERT INTO listings (external_id, source, title, price, url, version)
                VALUES (?, 'WG_GESUCHT', 'Wohnung ' || ?, 1000, 'https://example/' || ?, 0) RETURNING id""",
                Long.class, externalId, externalId, externalId);
    }

    // ==================== Search criteria ====================

    @Nested
    @DisplayName("/api/criteria")
    class Criteria {

        @Test
        @DisplayName("POST creates criteria and the user behind it")
        void create() throws Exception {
            long chatId = newChatId();

            postJson("/api/criteria", """
                    {"telegramChatId": %d, "maxRent": 1200.00, "minRooms": 2, "maxRooms": 3,
                     "minSizeSqm": 40, "maxSizeSqm": 80, "districts": ["Maxvorstadt", "Schwabing"],
                     "ubahnLines": ["U3", "U6"]}""".formatted(chatId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.maxRent").value(1200.0))
                    .andExpect(jsonPath("$.districts", hasSize(2)))
                    .andExpect(jsonPath("$.ubahnLines[1]").value("U6"))
                    .andExpect(jsonPath("$.active").value(true));

            mvc.perform(get("/api/users/by-chat/" + chatId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        @DisplayName("POST for an existing chat reuses the user")
        void createForExistingUser() throws Exception {
            long chatId = newChatId();
            createCriteria(chatId, "");
            createCriteria(chatId, ", \"maxRent\": 900");

            mvc.perform(get("/api/criteria/user/" + userIdOf(chatId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
            mvc.perform(get("/api/users")).andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("POST validates every field")
        void validation() throws Exception {
            postJson("/api/criteria", """
                    {"maxRent": -1, "minRooms": 0, "maxRooms": 0.5, "minSizeSqm": 0, "maxSizeSqm": -3}""")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Validation Failed"))
                    .andExpect(jsonPath("$.fieldErrors.telegramChatId").exists())
                    .andExpect(jsonPath("$.fieldErrors.maxRent").exists())
                    .andExpect(jsonPath("$.fieldErrors.minRooms").exists())
                    .andExpect(jsonPath("$.fieldErrors.maxRooms").exists())
                    .andExpect(jsonPath("$.fieldErrors.minSizeSqm").exists())
                    .andExpect(jsonPath("$.fieldErrors.maxSizeSqm").exists());
        }

        @Test
        @DisplayName("POST rejects a minimum above the maximum")
        void minAboveMax() throws Exception {
            long chatId = newChatId();

            postJson("/api/criteria", "{\"telegramChatId\": %d, \"minRooms\": 4, \"maxRooms\": 2}".formatted(chatId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("minRooms")));
            postJson("/api/criteria", "{\"telegramChatId\": %d, \"minSizeSqm\": 90, \"maxSizeSqm\": 40}".formatted(chatId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("minSizeSqm")));
        }

        @Test
        @DisplayName("malformed JSON is a 400, not a 500")
        void malformedJson() throws Exception {
            postJson("/api/criteria", "{\"telegramChatId\": ")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed or missing request body"));
            postJson("/api/criteria", "{\"telegramChatId\": \"not a number\"}")
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET for a user without criteria returns an empty list")
        void emptyList() throws Exception {
            mvc.perform(get("/api/criteria/user/999999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("PUT changes only the fields that are sent")
        void partialUpdate() throws Exception {
            long chatId = newChatId();
            long id = createCriteria(chatId, ", \"maxRent\": 1000, \"minRooms\": 2, \"districts\": [\"Laim\"]");

            putJson("/api/criteria/" + id, "{\"telegramChatId\": %d, \"maxRent\": 1400}".formatted(chatId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.maxRent").value(1400.0))
                    .andExpect(jsonPath("$.minRooms").value(2.0))
                    .andExpect(jsonPath("$.districts[0]").value("Laim"));
        }

        @Test
        @DisplayName("PUT can change every field at once")
        void fullUpdate() throws Exception {
            long chatId = newChatId();
            long id = createCriteria(chatId, "");

            putJson("/api/criteria/" + id, """
                    {"telegramChatId": %d, "maxRent": 1800, "minRooms": 2, "maxRooms": 4, "minSizeSqm": 50,
                     "maxSizeSqm": 100, "districts": ["Pasing"], "ubahnLines": ["U5"]}""".formatted(chatId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.maxRooms").value(4.0))
                    .andExpect(jsonPath("$.maxSizeSqm").value(100))
                    .andExpect(jsonPath("$.districts[0]").value("Pasing"))
                    .andExpect(jsonPath("$.ubahnLines[0]").value("U5"));
        }

        @Test
        @DisplayName("PUT checks min/max against the values already stored")
        void updateValidatesAgainstStoredValues() throws Exception {
            long chatId = newChatId();
            long id = createCriteria(chatId, ", \"minRooms\": 2, \"maxRooms\": 3");

            putJson("/api/criteria/" + id, "{\"telegramChatId\": %d, \"minRooms\": 5}".formatted(chatId))
                    .andExpect(status().isBadRequest());
            putJson("/api/criteria/" + id, "{\"telegramChatId\": %d, \"minSizeSqm\": 50, \"maxSizeSqm\": 60}".formatted(chatId))
                    .andExpect(status().isOk());
            putJson("/api/criteria/" + id, "{\"telegramChatId\": %d, \"maxSizeSqm\": 45}".formatted(chatId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PUT and DELETE of unknown criteria are 404")
        void unknownCriteria() throws Exception {
            putJson("/api/criteria/424242", "{\"telegramChatId\": 1}")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", containsString("424242")));
            mvc.perform(delete("/api/criteria/424242")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("DELETE removes criteria; deleting again is 404")
        void deleteCriteria() throws Exception {
            long chatId = newChatId();
            long id = createCriteria(chatId, "");

            mvc.perform(delete("/api/criteria/" + id)).andExpect(status().isNoContent());
            mvc.perform(delete("/api/criteria/" + id)).andExpect(status().isNotFound());
            mvc.perform(get("/api/criteria/user/" + userIdOf(chatId))).andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ==================== Users ====================

    @Nested
    @DisplayName("/api/users")
    class Users {

        @Test
        @DisplayName("lookup by id and by chat id; unknown ones are 404")
        void lookup() throws Exception {
            long chatId = newChatId();
            userSends(chatId, "/start");
            long id = userIdOf(chatId);

            mvc.perform(get("/api/users/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.telegramChatId").value(chatId))
                    .andExpect(jsonPath("$.firstName").value("Kagan"));
            mvc.perform(get("/api/users/by-chat/" + chatId))
                    .andExpect(jsonPath("$.id").value(id));
            mvc.perform(get("/api/users/999999")).andExpect(status().isNotFound());
            mvc.perform(get("/api/users/by-chat/999999")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("deactivate/activate toggle the user and the active-users list")
        void activation() throws Exception {
            long chatId = newChatId();
            userSends(chatId, "/start");
            long id = userIdOf(chatId);

            mvc.perform(post("/api/users/" + id + "/deactivate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            mvc.perform(get("/api/users")).andExpect(jsonPath("$", hasSize(0)));

            mvc.perform(post("/api/users/" + id + "/activate"))
                    .andExpect(jsonPath("$.active").value(true));
            mvc.perform(get("/api/users")).andExpect(jsonPath("$", hasSize(1)));

            mvc.perform(post("/api/users/999999/activate")).andExpect(status().isNotFound());
            mvc.perform(post("/api/users/999999/deactivate")).andExpect(status().isNotFound());
        }
    }

    // ==================== Listings ====================

    @Nested
    @DisplayName("/api/listings")
    class Listings {

        @Test
        @DisplayName("GET pages newest first and caps the page size at 100")
        void paging() throws Exception {
            for (int i = 0; i < 3; i++) {
                insertListing("L" + i);
            }
            jdbc.update("UPDATE listings SET scraped_at = now() - (id || ' minutes')::interval");

            mvc.perform(get("/api/listings").param("page", "0").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.content[0].externalId").value("L0"))
                    .andExpect(jsonPath("$.totalElements").value(3));
            mvc.perform(get("/api/listings").param("size", "500"))
                    .andExpect(jsonPath("$.size").value(100));
        }

        @Test
        @DisplayName("GET one listing; unknown id is 404, non-numeric id is 400")
        void getOne() throws Exception {
            long id = insertListing("X1");

            mvc.perform(get("/api/listings/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Wohnung X1"))
                    .andExpect(jsonPath("$.description").value(nullValue()));
            mvc.perform(get("/api/listings/999999")).andExpect(status().isNotFound());
            mvc.perform(get("/api/listings/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("'id'")));
        }

        @Test
        @DisplayName("DELETE also removes the listing's notifications")
        void deleteWithNotifications() throws Exception {
            long chatId = newChatId();
            userSends(chatId, "/start");
            long listingId = insertListing("D1");
            jdbc.update("INSERT INTO notifications (user_id, listing_id, status) VALUES (?, ?, 'SENT')",
                    userIdOf(chatId), listingId);

            mvc.perform(delete("/api/listings/" + listingId)).andExpect(status().isNoContent());

            mvc.perform(get("/api/listings/" + listingId)).andExpect(status().isNotFound());
            Integer left = jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class);
            org.assertj.core.api.Assertions.assertThat(left).isZero();
            mvc.perform(delete("/api/listings/" + listingId)).andExpect(status().isNotFound());
        }
    }

    // ==================== Misc ====================

    @Test
    @DisplayName("health endpoints report UP")
    void health() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("IsarAlert"));
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("unknown routes are 404 and wrong HTTP methods are 405, not 500")
    void frameworkErrors() throws Exception {
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/listings/1")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("the OpenAPI spec is served")
    void openApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("IsarAlert API"));
    }
}

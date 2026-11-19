package cn.swjtu.zhixing.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.swjtu.zhixing.assistant.ToolCallingClient;
import cn.swjtu.zhixing.serviceconfig.PersonalServices.Credentials;
import cn.swjtu.zhixing.transit.TransitErrorCode;
import cn.swjtu.zhixing.transit.TransitProvider;
import cn.swjtu.zhixing.transit.TransitSearchResult;
import cn.swjtu.zhixing.transit.TransitStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServiceConfigControllerTests {
    private static final String OWNER = PersonalServices.OWNER_HEADER;
    private static final String PATH = "/api/service-config";
    private static final String VERIFIED = "1".repeat(32);
    private static final String REPLACEMENT = "2".repeat(32);

    private final PersonalServices store = new PersonalServices();
    private final TransitProvider transit = mock(TransitProvider.class);
    private final MockMvc api = MockMvcBuilders.standaloneSetup(
                    new ServiceConfigController(store, transit, mock(ToolCallingClient.class)))
            .setControllerAdvice(new ServiceConfigErrors())
            .build();

    @Test
    void requiresHttpsAndAnIndependentOwner() throws Exception {
        api.perform(post(PATH + "/sessions")).andExpect(status().isForbidden());
        String owner = store.create().ownerToken();
        String other = store.create().ownerToken();
        when(transit.searchWithKey(any(), eq(VERIFIED))).thenReturn(result(TransitErrorCode.NO_ROUTE));
        api.perform(put(PATH + "/amap").secure(true).header(OWNER, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(jsonKey(VERIFIED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amapVerified").value(true))
                .andExpect(jsonPath("$.key").doesNotExist());
        api.perform(get(PATH).secure(true).header(OWNER, other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amapVerified").value(false));
        api.perform(get(PATH).secure(true).header(OWNER, "A".repeat(43)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectedUpdatesLeaveThePreviousKeyInPlace() throws Exception {
        String owner = store.create().ownerToken();
        var original = new Credentials(VERIFIED, null, null);
        store.replace(owner, store.require(owner), original);
        when(transit.searchWithKey(any(), eq(REPLACEMENT))).thenReturn(
                result(TransitErrorCode.AMAP_KEY_PLATFORM_MISMATCH),
                result(TransitErrorCode.AMAP_QUOTA_ERROR),
                result(TransitErrorCode.NETWORK_TIMEOUT));
        for (int expected : List.of(400, 429, 504)) {
            String response = api.perform(put(PATH + "/amap").secure(true).header(OWNER, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(jsonKey(REPLACEMENT)))
                    .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(VERIFIED, REPLACEMENT);
            assertThat(store.require(owner).credentials()).isSameAs(original);
        }
    }

    @Test
    void revocationInvalidatesAConcurrentVerification() throws Exception {
        String owner = store.create().ownerToken();
        store.replace(owner, store.require(owner), new Credentials(VERIFIED, null, null));
        var inflight = store.require(owner);
        api.perform(delete(PATH + "/amap").secure(true).header(OWNER, owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amapVerified").value(false));
        assertThatThrownBy(() -> store.replace(owner, inflight, new Credentials(REPLACEMENT, null, null)))
                .isInstanceOf(ServiceConfigException.class);
        api.perform(delete(PATH).secure(true).header(OWNER, owner)).andExpect(status().isNoContent());
        assertThatThrownBy(() -> store.require(owner)).isInstanceOf(ServiceConfigException.class);
    }

    private static String jsonKey(String key) {
        return "{\"key\":\"" + key + "\"}";
    }

    private static TransitSearchResult result(TransitErrorCode error) {
        return new TransitSearchResult("AMAP", null, null, null, 0, TransitStatus.NO_ROUTE, List.of(), error, null);
    }
}

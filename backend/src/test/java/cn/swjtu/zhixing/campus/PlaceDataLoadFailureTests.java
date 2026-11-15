package cn.swjtu.zhixing.campus;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 数据文件缺失时，接口返回明确的加载错误而不是静默空列表。 */
@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "campus.data-root=/tmp/llm-route-missing-data-root",
        "campus.application-places=classpath:data/application-places.json"
})
class PlaceDataLoadFailureTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchReportsDataLoadFailure() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "图书馆"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PLACE_DATA_LOAD_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void getPlaceByIdReportsDataLoadFailure() throws Exception {
        mockMvc.perform(get("/api/places/swjtu_org_100"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("PLACE_DATA_LOAD_FAILED"));
    }
}

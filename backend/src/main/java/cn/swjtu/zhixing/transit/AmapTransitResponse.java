package cn.swjtu.zhixing.transit;

import com.fasterxml.jackson.databind.JsonNode;

/** 高德 v3 公交接口的顶层响应 DTO；route 保留为树以兼容公交、地铁和铁路分支。 */
public record AmapTransitResponse(String status, String info, String infocode, JsonNode route) {
}

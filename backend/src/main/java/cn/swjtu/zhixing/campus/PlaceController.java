package cn.swjtu.zhixing.campus;

import cn.swjtu.zhixing.serviceconfig.PersonalServices;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地点接口：
 * <ul>
 *   <li>GET /api/places?keyword=图书馆&amp;scope=CAMPUS —— 返回 {items:[PlaceCandidate]}，未知关键词 200 + 空 items；</li>
 *   <li>GET /api/places/{id} —— 返回 PlaceCandidate，未知 ID 404 + PLACE_NOT_FOUND。</li>
 * </ul>
 * 契约见 docs/api.md。
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService placeService;
    private final PersonalServices personalServices;

    public PlaceController(PlaceService placeService, PersonalServices personalServices) {
        this.placeService = placeService;
        this.personalServices = personalServices;
    }

    @GetMapping
    public PlaceSearchResponse search(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestHeader(name = PersonalServices.OWNER_HEADER, required = false) String token,
            HttpServletRequest request) {
        PlaceKind kind = PlaceKind.fromScope(scope);
        var credentials = kind == PlaceKind.CITY ? personalServices.forRequest(token, request) : null;
        return new PlaceSearchResponse(placeService.search(keyword, kind, credentials));
    }

    @GetMapping("/{id}")
    public PlaceCandidate getById(@PathVariable("id") String id,
                                  @RequestHeader(name = PersonalServices.OWNER_HEADER, required = false) String token,
                                  HttpServletRequest request) {
        var credentials = AmapCityPlaces.isOnlineId(id) ? personalServices.forRequest(token, request) : null;
        return placeService.getById(id, credentials);
    }
}

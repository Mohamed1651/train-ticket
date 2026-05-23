package foodsearch.service;

import edu.fudan.common.util.JsonUtils;
import edu.fudan.common.util.Response;
import foodsearch.entity.*;
import foodsearch.mq.RabbitSend;
import foodsearch.repository.FoodOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FoodServiceImpl implements FoodService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private FoodOrderRepository foodOrderRepository;

    @Autowired
    private RabbitSend sender;

    private static final Logger LOGGER = LoggerFactory.getLogger(FoodServiceImpl.class);

    String success = "Success.";
    String orderIdNotExist = "Order Id Is Non-Existent.";

    public Response createFoodOrdersInBatch(List<FoodOrder> orders, HttpHeaders headers) {
        boolean error = false;
        String errorOrderId = "";

        // Check if foodOrder exists
        for (FoodOrder addFoodOrder : orders) {
            FoodOrder fo = foodOrderRepository.findByOrderId(addFoodOrder.getOrderId());
            if (fo != null) {
                LOGGER.error("[AddFoodOrder] Order Id Has Existed, OrderId: {}", addFoodOrder.getOrderId());
                error = true;
                errorOrderId = addFoodOrder.getOrderId().toString();
                break;
            }
        }
        if (error) {
            return new Response<>(0, "Order Id " + errorOrderId + "Existed", null);
        }

        List<String> deliveryJsons = new ArrayList<>();
        for (FoodOrder addFoodOrder : orders) {
            FoodOrder fo = new FoodOrder();
            fo.setId(UUID.randomUUID());
            fo.setOrderId(addFoodOrder.getOrderId());
            fo.setFoodType(addFoodOrder.getFoodType());
            if (addFoodOrder.getFoodType() == 2) {
                fo.setStationName(addFoodOrder.getStationName());
                fo.setStoreName(addFoodOrder.getStoreName());
            }
            fo.setFoodName(addFoodOrder.getFoodName());
            fo.setPrice(addFoodOrder.getPrice());
            foodOrderRepository.save(fo);
            LOGGER.info("[AddFoodOrderBatch] Success Save One Order [{}]", fo.getOrderId());

            Delivery delivery = new Delivery();
            delivery.setFoodName(addFoodOrder.getFoodName());
            delivery.setOrderId(addFoodOrder.getOrderId());
            delivery.setStationName(addFoodOrder.getStationName());
            delivery.setStoreName(addFoodOrder.getStoreName());

            String deliveryJson = JsonUtils.object2Json(delivery);
            deliveryJsons.add(deliveryJson);
        }

        // 批量发送消息
        for(String deliveryJson: deliveryJsons) {
            LOGGER.info("[AddFoodOrder] delivery info [{}] send to mq", deliveryJson);
            try {
                sender.send(deliveryJson);
            } catch (Exception e) {
                LOGGER.error("[AddFoodOrder] send delivery info to mq error, exception is [{}]", e.toString());
            }
        }

        return new Response<>(1, success, null);
    }

    @Override
    public Response createFoodOrder(FoodOrder addFoodOrder, HttpHeaders headers) {

        FoodOrder fo = foodOrderRepository.findByOrderId(addFoodOrder.getOrderId());
        if (fo != null) {
            FoodServiceImpl.LOGGER.error("[AddFoodOrder] Order Id Has Existed, OrderId: {}", addFoodOrder.getOrderId());
            return new Response<>(0, "Order Id Has Existed.", null);
        } else {
            fo = new FoodOrder();
            fo.setId(UUID.randomUUID());
            fo.setOrderId(addFoodOrder.getOrderId());
            fo.setFoodType(addFoodOrder.getFoodType());
            if (addFoodOrder.getFoodType() == 2) {
                fo.setStationName(addFoodOrder.getStationName());
                fo.setStoreName(addFoodOrder.getStoreName());
            }
            fo.setFoodName(addFoodOrder.getFoodName());
            fo.setPrice(addFoodOrder.getPrice());
            foodOrderRepository.save(fo);
            FoodServiceImpl.LOGGER.info("[AddFoodOrder] Success.");

            Delivery delivery = new Delivery();
            delivery.setFoodName(addFoodOrder.getFoodName());
            delivery.setOrderId(addFoodOrder.getOrderId());
            delivery.setStationName(addFoodOrder.getStationName());
            delivery.setStoreName(addFoodOrder.getStoreName());

            String deliveryJson = JsonUtils.object2Json(delivery);
            LOGGER.info("[AddFoodOrder] delivery info [{}] send to mq", deliveryJson);
            try {
                sender.send(deliveryJson);
            } catch (Exception e) {
                LOGGER.error("[AddFoodOrder] send delivery info to mq error, exception is [{}]", e.toString());
            }

            return new Response<>(1, success, fo);
        }
    }

    @Override
    public Response deleteFoodOrder(String orderId, HttpHeaders headers) {
        FoodOrder foodOrder = foodOrderRepository.findByOrderId(UUID.fromString(orderId));
        if (foodOrder == null) {
            FoodServiceImpl.LOGGER.error("[Cancel FoodOrder] Order Id Is Non-Existent, orderId: {}", orderId);
            return new Response<>(0, orderIdNotExist, null);
        } else {
            foodOrderRepository.deleteFoodOrderByOrderId(UUID.fromString(orderId));
            FoodServiceImpl.LOGGER.info("[Cancel FoodOrder] Success.");
            return new Response<>(1, success, null);
        }
    }

    @Override
    public Response findAllFoodOrder(HttpHeaders headers) {
        List<FoodOrder> foodOrders = foodOrderRepository.findAll();
        if (foodOrders != null && !foodOrders.isEmpty()) {
            return new Response<>(1, success, foodOrders);
        } else {
            FoodServiceImpl.LOGGER.error("Find all food order error: {}", "No Content");
            return new Response<>(0, "No Content", null);
        }
    }


    @Override
    public Response updateFoodOrder(FoodOrder updateFoodOrder, HttpHeaders headers) {
        FoodOrder fo = foodOrderRepository.findById(updateFoodOrder.getId());
        if (fo == null) {
            FoodServiceImpl.LOGGER.info("[Update FoodOrder] Order Id Is Non-Existent, orderId: {}", updateFoodOrder.getOrderId());
            return new Response<>(0, orderIdNotExist, null);
        } else {
            fo.setFoodType(updateFoodOrder.getFoodType());
            if (updateFoodOrder.getFoodType() == 1) {
                fo.setStationName(updateFoodOrder.getStationName());
                fo.setStoreName(updateFoodOrder.getStoreName());
            }
            fo.setFoodName(updateFoodOrder.getFoodName());
            fo.setPrice(updateFoodOrder.getPrice());
            foodOrderRepository.save(fo);
            FoodServiceImpl.LOGGER.info("[Update FoodOrder] Success.");
            return new Response<>(1, "Success", fo);
        }
    }

    @Override
    public Response findByOrderId(String orderId, HttpHeaders headers) {
        FoodOrder fo = foodOrderRepository.findByOrderId(UUID.fromString(orderId));
        if (fo != null) {
            FoodServiceImpl.LOGGER.info("[Find Order by id] Success.");
            return new Response<>(1, success, fo);
        } else {
            FoodServiceImpl.LOGGER.info("[Find Order by id] Order Id Is Non-Existent, orderId: {}", orderId);
            return new Response<>(0, orderIdNotExist, null);
        }
    }

    @Override
    public Response getAllFood(String date, String startStation, String endStation, String tripId, HttpHeaders headers) {
        FoodServiceImpl.LOGGER.info("data={} start={} end={} tripid={}", date, startStation, endStation, tripId);
        if (isInvalidTripId(tripId)) {
            FoodServiceImpl.LOGGER.error("Get the Get Food Request Failed! Trip id is not suitable, date: {}, tripId: {}", date, tripId);
            return new Response<>(0, "Trip id is not suitable", null);
        }

        List<TrainFood> trainFoodList = fetchTrainFoodList(tripId);
        if (trainFoodList == null) {
            FoodServiceImpl.LOGGER.error("Get the Get Food Request Failed!, date: {}, tripId: {}", date, tripId);
            return new Response<>(0, "Get the Get Food Request Failed!", null);
        }

        Response<Route> stationResult = fetchRoute(tripId);
        AllTripFood allTripFood = new AllTripFood();

        if (isInvalidStationResult(stationResult)) {
            FoodServiceImpl.LOGGER.error("Get the Get Food Request Failed! station status error, date: {}, tripId: {}", date, tripId);
            return new Response<>(0, "Get All Food Failed", allTripFood);
        }

        List<String> stations = stationResult.getData().getStations();
        List<String> filteredStations = filterStations(stations, startStation, endStation);

        Map<String, List<FoodStore>> foodStoreListMap = fetchAndMapFoodStores(filteredStations, date, tripId);
        if (foodStoreListMap.isEmpty()) {
            return new Response<>(0, "Get All Food Failed", allTripFood);
        }

        allTripFood.setTrainFoodList(trainFoodList);
        allTripFood.setFoodStoreListMap(foodStoreListMap);
        return new Response<>(1, "Get All Food Success", allTripFood);
    }

    private boolean isInvalidTripId(String tripId) {
        return null == tripId || tripId.length() <= 2;
    }

    private boolean isInvalidStationResult(Response<Route> stationResult) {
        return stationResult == null || stationResult.getStatus() != 1 || stationResult.getData() == null;
    }

    private Response<Route> fetchRoute(String tripId) {
        HttpEntity<Void> requestEntity = new HttpEntity<>(null);
        ResponseEntity<Response<Route>> response = restTemplate.exchange(
                "http://ts-travel-service:12346/api/v1/travelservice/routes/" + tripId,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<Route>>() {}
        );
        return response.getBody();
    }

    private List<TrainFood> fetchTrainFoodList(String tripId) {
        HttpEntity<Void> requestEntity = new HttpEntity<>(null);
        ResponseEntity<Response<List<TrainFood>>> response = restTemplate.exchange(
                "http://ts-food-map-service:18855/api/v1/foodmapservice/trainfoods/" + tripId,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<List<TrainFood>>>() {}
        );
        return response.getBody() != null ? response.getBody().getData() : null;
    }

    private String fetchStationId(String stationName) {
        HttpEntity<Void> requestEntity = new HttpEntity<>(null);
        ResponseEntity<Response<String>> response = restTemplate.exchange(
                "http://ts-station-service:12345/api/v1/stationservice/stations/id/" + stationName,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Response<String>>() {}
        );
        return response.getBody() != null ? response.getBody().getData() : null;
    }

    private List<String> filterStations(List<String> stations, String startStation, String endStation) {
        int startIndex = java.util.Optional.ofNullable(startStation)
                .filter(s -> !s.isEmpty())
                .map(this::fetchStationId)
                .map(stations::indexOf)
                .orElse(0);

        int endIndex = java.util.Optional.ofNullable(endStation)
                .filter(s -> !s.isEmpty())
                .map(this::fetchStationId)
                .map(stations::indexOf)
                .orElse(stations.size() - 1);

        // Safeguard indices against missing stations (-1) or inverted routes
        int actualStart = Math.max(0, startIndex);
        int actualEnd = endIndex >= 0 && endIndex >= actualStart ? endIndex : stations.size() - 1;

        return stations.subList(actualStart, actualEnd + 1);
    }

    private Map<String, List<FoodStore>> fetchAndMapFoodStores(List<String> stations, String date, String tripId) {
        HttpEntity<List<String>> requestEntity = new HttpEntity<>(stations, null);
        ResponseEntity<Response<List<FoodStore>>> response = restTemplate.exchange(
                "http://ts-food-map-service:18855/api/v1/foodmapservice/foodstores",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Response<List<FoodStore>>>() {}
        );

        List<FoodStore> foodStores = response.getBody() != null ? response.getBody().getData() : null;
        if (foodStores == null || foodStores.isEmpty()) {
            FoodServiceImpl.LOGGER.error("Get the Get Food Request Failed! foodStoresListResult is null, date: {}, tripId: {}", date, tripId);
            return Collections.emptyMap();
        }

        return mapStoresToStations(stations, foodStores);
    }

    private Map<String, List<FoodStore>> mapStoresToStations(List<String> stations, List<FoodStore> foodStores) {
        // GroupingBy drops complexity score to 0 instead of nesting loops/streams manually
        Map<String, List<FoodStore>> groupedStores = foodStores.stream()
                .collect(Collectors.groupingBy(FoodStore::getStationId));

        return stations.stream()
                .collect(Collectors.toMap(
                        stationId -> stationId,
                        stationId -> groupedStores.getOrDefault(stationId, Collections.emptyList()),
                        (oldValue, newValue) -> oldValue,
                        HashMap::new
                ));
    }
}

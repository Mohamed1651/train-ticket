package preserve.service;

import edu.fudan.common.util.JsonUtils;
import edu.fudan.common.util.Response;
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
import preserve.entity.*;
import preserve.mq.RabbitSend;

import java.util.Date;
import java.util.UUID;

/**
 * @author fdse
 */
@Service
public class PreserveServiceImpl implements PreserveService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private RabbitSend sendService;

    private static final Logger LOGGER = LoggerFactory.getLogger(PreserveServiceImpl.class);

    @Override
    public Response preserve(OrderTicketsInfo oti, HttpHeaders headers) {
        // Steps 1 & 2: Security & Contacts validation
        Response securityCheck = checkSecurityStep(oti, headers);
        if (securityCheck != null) return securityCheck;

        Response<Contacts> contactResponse = getContactsStep(oti, headers);
        if (contactResponse.getStatus() == 0) return new Response<>(0, contactResponse.getMsg(), null);

        // Step 3: Validate Ticket Availability
        Response<TripAllDetail> ticketCheck = checkTicketsStep(oti, headers);
        if (ticketCheck.getStatus() == 0) return new Response<>(0, ticketCheck.getMsg(), null);

        // Step 4: Create Core Order
        Response<Order> orderCreation = createOrderStep(oti, contactResponse.getData(), ticketCheck.getData(), headers);
        if (orderCreation.getStatus() == 0) return new Response<>(0, orderCreation.getMsg(), null);

        // Steps 5, 6, 7 & 8: Post-order optional additions and notification
        Response returnResponse = new Response<>(1, "Success.", orderCreation.getMsg());
        Order finalOrder = orderCreation.getData();

        processOptionalServices(oti, finalOrder, returnResponse, headers);
        sendNotificationStep(finalOrder, headers);

        return returnResponse;
    }

    private Response checkSecurityStep(OrderTicketsInfo oti, HttpHeaders headers) {
        PreserveServiceImpl.LOGGER.info("[Step 1] Check Security");
        Response result = checkSecurity(oti.getAccountId(), headers);
        if (result.getStatus() == 0) {
            PreserveServiceImpl.LOGGER.error("[Step 1] Check Security Fail, AccountId: {}", oti.getAccountId());
            return new Response<>(0, result.getMsg(), null);
        }
        PreserveServiceImpl.LOGGER.info("[Step 1] Check Security Complete");
        return null;
    }

    private Response<Contacts> getContactsStep(OrderTicketsInfo oti, HttpHeaders headers) {
        PreserveServiceImpl.LOGGER.info("[Step 2] Find contacts");
        PreserveServiceImpl.LOGGER.info("[Step 2] Contacts Id: {}", oti.getContactsId());
        Response<Contacts> gcr = getContactsById(oti.getContactsId(), headers);
        if (gcr.getStatus() == 0) {
            PreserveServiceImpl.LOGGER.error("[Get Contacts] Fail,ContactsId: {},message: {}", oti.getContactsId(), gcr.getMsg());
        } else {
            PreserveServiceImpl.LOGGER.info("[Step 2] Complete");
        }
        return gcr;
    }

    private Response<TripAllDetail> checkTicketsStep(OrderTicketsInfo oti, HttpHeaders headers) {
        PreserveServiceImpl.LOGGER.info("[Step 3] Check tickets num");
        TripAllDetailInfo gtdi = new TripAllDetailInfo();
        gtdi.setFrom(oti.getFrom());
        gtdi.setTo(oti.getTo());
        gtdi.setTravelDate(oti.getDate());
        gtdi.setTripId(oti.getTripId());

        Response<TripAllDetail> response = getTripAllDetailInformation(gtdi, headers);
        if (response.getStatus() == 0) {
            PreserveServiceImpl.LOGGER.error("[Search For Trip Detail Information] error, TripId: {}, message: {}", gtdi.getTripId(), response.getMsg());
            return new Response<>(0, response.getMsg(), null);
        }

        TripAllDetail gtdr = response.getData();
        TripResponse tripResponse = gtdr.getTripResponse();
        LOGGER.info("TripResponse: {}", tripResponse);

        boolean isFirstClass = oti.getSeatType() == SeatClass.FIRSTCLASS.getCode();
        if (isFirstClass && tripResponse.getConfortClass() == 0) {
            PreserveServiceImpl.LOGGER.warn("[Check seat is enough], TripId: {}", oti.getTripId());
            return new Response<>(0, "Seat Not Enough", null);
        } else if (!isFirstClass && tripResponse.getEconomyClass() == SeatClass.SECONDCLASS.getCode() && tripResponse.getConfortClass() == 0) {
            PreserveServiceImpl.LOGGER.warn("[Check seat is Not enough], TripId: {}", oti.getTripId());
            return new Response<>(0, "Seat Not Enough", null);
        }

        PreserveServiceImpl.LOGGER.info("[Step 3] Tickets Enough");
        return response;
    }

    private Response<Order> createOrderStep(OrderTicketsInfo oti, Contacts contacts, TripAllDetail gtdr, HttpHeaders headers) {
        PreserveServiceImpl.LOGGER.info("[Step 4] Do Order");
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setTrainNumber(oti.getTripId());
        order.setAccountId(UUID.fromString(oti.getAccountId()));

        String fromStationId = queryForStationId(oti.getFrom(), headers);
        String toStationId = queryForStationId(oti.getTo(), headers);
        order.setFrom(fromStationId);
        order.setTo(toStationId);
        order.setBoughtDate(new Date());
        order.setStatus(OrderStatus.NOTPAID.getCode());
        order.setContactsDocumentNumber(contacts.getDocumentNumber());
        order.setContactsName(contacts.getName());
        order.setDocumentType(contacts.getDocumentType());

        Travel query = new Travel();
        query.setTrip(gtdr.getTrip());
        query.setStartingPlace(oti.getFrom());
        query.setEndPlace(oti.getTo());
        query.setDepartureTime(new Date());

        HttpEntity requestEntity = new HttpEntity(query, headers);
        ResponseEntity<Response<TravelResult>> re = restTemplate.exchange(
                "http://ts-ticketinfo-service:15681/api/v1/ticketinfoservice/ticketinfo",
                HttpMethod.POST, requestEntity, new ParameterizedTypeReference<Response<TravelResult>>() {});
        TravelResult resultForTravel = re.getBody().getData();

        order.setSeatClass(oti.getSeatType());
        order.setTravelDate(oti.getDate());
        order.setTravelTime(gtdr.getTripResponse().getStartingTime());

        Ticket ticket = dipatchSeat(oti.getDate(), order.getTrainNumber(), fromStationId, toStationId, oti.getSeatType(), headers);
        order.setSeatNumber(String.valueOf(ticket.getSeatNo()));

        String priceKey = (oti.getSeatType() == SeatClass.FIRSTCLASS.getCode()) ? "confortClass" : "economyClass";
        order.setPrice(resultForTravel.getPrices().get(priceKey));

        PreserveServiceImpl.LOGGER.info("[Order Price] Price is: {}", order.getPrice());
        Response<Order> cor = createOrder(order, headers);
        if (cor.getStatus() == 0) {
            PreserveServiceImpl.LOGGER.error("[Create Order Fail] Create Order Fail. OrderId: {}, Reason: {}", order.getId(), cor.getMsg());
        } else {
            PreserveServiceImpl.LOGGER.info("[Step 4] Do Order Complete");
        }
        return cor;
    }

    private void processOptionalServices(OrderTicketsInfo oti, Order order, Response returnResponse, HttpHeaders headers) {
        // 5. Check insurance options
        if (oti.getAssurance() != 0) {
            Response addAssuranceResult = addAssuranceForOrder(oti.getAssurance(), order.getId().toString(), headers);
            if (addAssuranceResult.getStatus() == 1) {
                PreserveServiceImpl.LOGGER.info("[Step 5] Buy Assurance Success");
            } else {
                PreserveServiceImpl.LOGGER.warn("[Step 5] Buy Assurance Fail, assurance: {}, OrderId: {}", oti.getAssurance(), order.getId());
                returnResponse.setMsg("Success.But Buy Assurance Fail.");
            }
        }

        // 6. Increase the food order
        if (oti.getFoodType() != 0) {
            FoodOrder foodOrder = new FoodOrder();
            foodOrder.setOrderId(order.getId());
            foodOrder.setFoodType(oti.getFoodType());
            foodOrder.setFoodName(oti.getFoodName());
            foodOrder.setPrice(oti.getFoodPrice());
            if (oti.getFoodType() == 2) {
                foodOrder.setStationName(oti.getStationName());
                foodOrder.setStoreName(oti.getStoreName());
            }
            Response afor = createFoodOrder(foodOrder, headers);
            if (afor.getStatus() == 1) {
                PreserveServiceImpl.LOGGER.info("[Step 6] Buy Food Success");
            } else {
                PreserveServiceImpl.LOGGER.error("[Step 6] Buy Food Fail, OrderId: {}", order.getId());
                returnResponse.setMsg("Success.But Buy Food Fail.");
            }
        }

        // 7. Add consign
        if (oti.getConsigneeName() != null && !oti.getConsigneeName().isEmpty()) {
            Consign consignRequest = new Consign();
            consignRequest.setOrderId(order.getId());
            consignRequest.setAccountId(order.getAccountId());
            consignRequest.setHandleDate(oti.getHandleDate());
            consignRequest.setTargetDate(order.getTravelDate().toString());
            consignRequest.setFrom(order.getFrom());
            consignRequest.setTo(order.getTo());
            consignRequest.setConsignee(oti.getConsigneeName());
            consignRequest.setPhone(oti.getConsigneePhone());
            consignRequest.setWeight(oti.getConsigneeWeight());
            consignRequest.setWithin(oti.isWithin());

            Response icresult = createConsign(consignRequest, headers);
            if (icresult.getStatus() == 1) {
                PreserveServiceImpl.LOGGER.info("[Step 7] Consign Success");
            } else {
                PreserveServiceImpl.LOGGER.error("[Step 7] Preserve Consign Fail, OrderId: {}", order.getId());
                returnResponse.setMsg("Consign Fail.");
            }
        }
    }

    private void sendNotificationStep(Order order, HttpHeaders headers) {
        User getUser = getAccount(order.getAccountId().toString(), headers);
        NotifyInfo notifyInfo = new NotifyInfo();
        notifyInfo.setDate(new Date().toString());
        notifyInfo.setEmail(getUser.getEmail());
        notifyInfo.setStartingPlace(order.getFrom());
        notifyInfo.setEndPlace(order.getTo());
        notifyInfo.setUsername(getUser.getUserName());
        notifyInfo.setSeatNumber(order.getSeatNumber());
        notifyInfo.setOrderNumber(order.getId().toString());
        notifyInfo.setPrice(order.getPrice());
        notifyInfo.setSeatClass(SeatClass.getNameByCode(order.getSeatClass()));
        notifyInfo.setStartingTime(order.getTravelTime().toString());
    }


    public Ticket dipatchSeat(Date date, String tripId, String startStationId, String endStataionId, int seatType, HttpHeaders httpHeaders) {
        Seat seatRequest = new Seat();
        seatRequest.setTravelDate(date);
        seatRequest.setTrainNumber(tripId);
        seatRequest.setStartStation(startStationId);
        seatRequest.setDestStation(endStataionId);
        seatRequest.setSeatType(seatType);

        HttpEntity requestEntityTicket = new HttpEntity(seatRequest, httpHeaders);
        ResponseEntity<Response<Ticket>> reTicket = restTemplate.exchange(
                "http://ts-seat-service:18898/api/v1/seatservice/seats",
                HttpMethod.POST,
                requestEntityTicket,
                new ParameterizedTypeReference<Response<Ticket>>() {
                });

        return reTicket.getBody().getData();
    }

    public boolean sendEmail(NotifyInfo notifyInfo) {
        PreserveServiceImpl.LOGGER.info("[Preserve Service][Send Email] send email to mq");

        try {
            String infoJson = JsonUtils.object2Json(notifyInfo);
            sendService.send(infoJson);
        } catch (Exception e) {
            PreserveServiceImpl.LOGGER.error("[Preserve Service] send email to mq error, exception is:", e);
            return false;
        }

        return true;
    }

    public User getAccount(String accountId, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Cancel Order Service][Get Order By Id]");

        HttpEntity requestEntitySendEmail = new HttpEntity(httpHeaders);
        ResponseEntity<Response<User>> getAccount = restTemplate.exchange(
                "http://ts-user-service:12342/api/v1/userservice/users/id/" + accountId,
                HttpMethod.GET,
                requestEntitySendEmail,
                new ParameterizedTypeReference<Response<User>>() {
                });
        Response<User> result = getAccount.getBody();
        return result.getData();
    }

    private Response addAssuranceForOrder(int assuranceType, String orderId, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Service][Add Assurance For Order]");
        HttpEntity requestAddAssuranceResult = new HttpEntity(httpHeaders);
        ResponseEntity<Response> reAddAssuranceResult = restTemplate.exchange(
                "http://ts-assurance-service:18888/api/v1/assuranceservice/assurances/" + assuranceType + "/" + orderId,
                HttpMethod.GET,
                requestAddAssuranceResult,
                Response.class);

        return reAddAssuranceResult.getBody();
    }

    private String queryForStationId(String stationName, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Other Service][Get Station Name]");


        HttpEntity requestQueryForStationId = new HttpEntity(httpHeaders);
        ResponseEntity<Response<String>> reQueryForStationId = restTemplate.exchange(
                "http://ts-station-service:12345/api/v1/stationservice/stations/id/" + stationName,
                HttpMethod.GET,
                requestQueryForStationId,
                new ParameterizedTypeReference<Response<String>>() {
                });

        return reQueryForStationId.getBody().getData();
    }

    private Response checkSecurity(String accountId, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Other Service][Check Security] Checking....");

        HttpEntity requestCheckResult = new HttpEntity(httpHeaders);
        ResponseEntity<Response> reCheckResult = restTemplate.exchange(
                "http://ts-security-service:11188/api/v1/securityservice/securityConfigs/" + accountId,
                HttpMethod.GET,
                requestCheckResult,
                Response.class);

        return reCheckResult.getBody();
    }


    private Response<TripAllDetail> getTripAllDetailInformation(TripAllDetailInfo gtdi, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Other Service][Get Trip All Detail Information] Getting....");

        HttpEntity requestGetTripAllDetailResult = new HttpEntity(gtdi, httpHeaders);
        ResponseEntity<Response<TripAllDetail>> reGetTripAllDetailResult = restTemplate.exchange(
                "http://ts-travel-service:12346/api/v1/travelservice/trip_detail",
                HttpMethod.POST,
                requestGetTripAllDetailResult,
                new ParameterizedTypeReference<Response<TripAllDetail>>() {
                });

        return reGetTripAllDetailResult.getBody();
    }


    private Response<Contacts> getContactsById(String contactsId, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Other Service][Get Contacts By Id] Getting....");

        HttpEntity requestGetContactsResult = new HttpEntity(httpHeaders);
        ResponseEntity<Response<Contacts>> reGetContactsResult = restTemplate.exchange(
                "http://ts-contacts-service:12347/api/v1/contactservice/contacts/" + contactsId,
                HttpMethod.GET,
                requestGetContactsResult,
                new ParameterizedTypeReference<Response<Contacts>>() {
                });

        return reGetContactsResult.getBody();
    }

    private Response createOrder(Order coi, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Other Service][Get Contacts By Id] Creating....");

        HttpEntity requestEntityCreateOrderResult = new HttpEntity(coi, httpHeaders);
        ResponseEntity<Response<Order>> reCreateOrderResult = restTemplate.exchange(
                "http://ts-order-service:12031/api/v1/orderservice/order",
                HttpMethod.POST,
                requestEntityCreateOrderResult,
                new ParameterizedTypeReference<Response<Order>>() {
                });

        return reCreateOrderResult.getBody();
    }

    private Response createFoodOrder(FoodOrder afi, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Service][Add food Order] Creating....");

        HttpEntity requestEntityAddFoodOrderResult = new HttpEntity(afi, httpHeaders);
        ResponseEntity<Response> reAddFoodOrderResult = restTemplate.exchange(
                "http://ts-food-service:18856/api/v1/foodservice/orders",
                HttpMethod.POST,
                requestEntityAddFoodOrderResult,
                Response.class);

        return reAddFoodOrderResult.getBody();
    }

    private Response createConsign(Consign cr, HttpHeaders httpHeaders) {
        PreserveServiceImpl.LOGGER.info("[Preserve Service][Add Condign] Creating....");

        HttpEntity requestEntityResultForTravel = new HttpEntity(cr, httpHeaders);
        ResponseEntity<Response> reResultForTravel = restTemplate.exchange(
                "http://ts-consign-service:16111/api/v1/consignservice/consigns",
                HttpMethod.POST,
                requestEntityResultForTravel,
                Response.class);
        return reResultForTravel.getBody();
    }

}

package inside_payment.service;

import edu.fudan.common.util.Response;
import inside_payment.entity.*;
import inside_payment.repository.AddMoneyRepository;
import inside_payment.repository.PaymentRepository;
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

import java.math.BigDecimal;
import java.util.*;

/**
 * @author fdse
 */
@Service
public class InsidePaymentServiceImpl implements InsidePaymentService {

    @Autowired
    public AddMoneyRepository addMoneyRepository;

    @Autowired
    public PaymentRepository paymentRepository;

    @Autowired
    public RestTemplate restTemplate;

    private static final Logger LOGGER = LoggerFactory.getLogger(InsidePaymentServiceImpl.class);

    @Override
    public Response pay(PaymentInfo info, HttpHeaders headers) {
        String userId = info.getUserId();
        String requestOrderURL = getOrderUrl(info.getTripId(), info.getOrderId());

        HttpEntity<Void> requestGetOrderResults = new HttpEntity<>(headers);
        ResponseEntity<Response<Order>> reGetOrderResults = restTemplate.exchange(
                requestOrderURL,
                HttpMethod.GET,
                requestGetOrderResults,
                new ParameterizedTypeReference<Response<Order>>() {});
        Response<Order> result = reGetOrderResults.getBody();

        if (result == null || result.getStatus() != 1) {
            LOGGER.error("Payment failed: Order not exists, orderId: {}", info.getOrderId());
            return new Response<>(0, "Payment Failed, Order Not Exists", null);
        }

        Order order = result.getData();
        if (order.getStatus() != OrderStatus.NOTPAID.getCode()) {
            InsidePaymentServiceImpl.LOGGER.info("[Inside Payment Service][Pay] Error. Order status Not allowed to Pay.");
            return new Response<>(0, "Error. Order status Not allowed to Pay.", null);
        }

        Payment payment = new Payment();
        payment.setOrderId(info.getOrderId());
        payment.setPrice(order.getPrice());
        payment.setUserId(userId);

        BigDecimal totalExpand = calculateTotalExpenditure(userId, order.getPrice());
        BigDecimal totalMoney = calculateTotalBalance(userId);

        if (totalExpand.compareTo(totalMoney) > 0) {
            return handleOutsidePayment(info, order.getPrice(), payment, headers);
        }

        setOrderStatus(info.getTripId(), info.getOrderId(), headers);
        payment.setType(PaymentType.P);
        paymentRepository.save(payment);

        LOGGER.info("Payment success, orderId: {}", info.getOrderId());
        return new Response<>(1, "Payment Success", null);
    }

    private String getOrderUrl(String tripId, String orderId) {
        if (tripId.startsWith("G") || tripId.startsWith("D")) {
            return "http://ts-order-service:12031/api/v1/orderservice/order/" + orderId;
        }
        return "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther/" + orderId;
    }

    private BigDecimal calculateTotalExpenditure(String userId, String currentOrderPrice) {
        List<Payment> payments = paymentRepository.findByUserId(userId);
        BigDecimal totalExpand = payments.stream()
                .map(p -> new BigDecimal(p.getPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalExpand.add(new BigDecimal(currentOrderPrice));
    }

    private BigDecimal calculateTotalBalance(String userId) {
        List<Money> addMonies = addMoneyRepository.findByUserId(userId);
        return addMonies.stream()
                .map(m -> new BigDecimal(m.getMoney()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Response handleOutsidePayment(PaymentInfo info, String orderPrice, Payment payment, HttpHeaders headers) {
        Payment outsidePaymentInfo = new Payment();
        outsidePaymentInfo.setOrderId(info.getOrderId());
        outsidePaymentInfo.setUserId(info.getUserId());
        outsidePaymentInfo.setPrice(orderPrice);

        HttpEntity<Payment> requestEntityOutsidePaySuccess = new HttpEntity<>(outsidePaymentInfo, headers);
        ResponseEntity<Response> reOutsidePaySuccess = restTemplate.exchange(
                "http://ts-payment-service:19001/api/v1/paymentservice/payment",
                HttpMethod.POST,
                requestEntityOutsidePaySuccess,
                Response.class);
        Response outsidePaySuccess = reOutsidePaySuccess.getBody();

        InsidePaymentServiceImpl.LOGGER.info("Out pay result: {}", outsidePaySuccess);
        if (outsidePaySuccess != null && outsidePaySuccess.getStatus() == 1) {
            payment.setType(PaymentType.O);
            paymentRepository.save(payment);
            setOrderStatus(info.getTripId(), info.getOrderId(), headers);
            return new Response<>(1, "Payment Success " + outsidePaySuccess.getMsg(), null);
        }

        String errorMsg = outsidePaySuccess != null ? outsidePaySuccess.getMsg() : "Unknown Error";
        LOGGER.error("Payment failed: {}", errorMsg);
        return new Response<>(0, "Payment Failed:  " + errorMsg, null);
    }

    @Override
    public Response createAccount(AccountInfo info, HttpHeaders headers) {
        List<Money> list = addMoneyRepository.findByUserId(info.getUserId());
        if (list.isEmpty()) {
            Money addMoney = new Money();
            addMoney.setMoney(info.getMoney());
            addMoney.setUserId(info.getUserId());
            addMoney.setType(MoneyType.A);
            addMoneyRepository.save(addMoney);
            return new Response<>(1, "Create Account Success", null);
        } else {
            LOGGER.error("Create Account Failed, Account already Exists, userId: {}", info.getUserId());
            return new Response<>(0, "Create Account Failed, Account already Exists", null);
        }
    }

    @Override
    public Response addMoney(String userId, String money, HttpHeaders headers) {
        if (addMoneyRepository.findByUserId(userId) != null) {
            Money addMoney = new Money();
            addMoney.setUserId(userId);
            addMoney.setMoney(money);
            addMoney.setType(MoneyType.A);
            addMoneyRepository.save(addMoney);
            return new Response<>(1, "Add Money Success", null);
        } else {
            LOGGER.error("Add Money Failed, userId: {}", userId);
            return new Response<>(0, "Add Money Failed", null);
        }
    }

    @Override
    public Response queryAccount(HttpHeaders headers) {
        List<Balance> result = new ArrayList<>();
        List<Money> list = addMoneyRepository.findAll();
        Iterator<Money> ite = list.iterator();
        HashMap<String, String> map = new HashMap<>();
        while (ite.hasNext()) {
            Money addMoney = ite.next();
            if (map.containsKey(addMoney.getUserId())) {
                BigDecimal money = new BigDecimal(map.get(addMoney.getUserId()));
                map.put(addMoney.getUserId(), money.add(new BigDecimal(addMoney.getMoney())).toString());
            } else {
                map.put(addMoney.getUserId(), addMoney.getMoney());
            }
        }

        Iterator ite1 = map.entrySet().iterator();
        while (ite1.hasNext()) {
            Map.Entry entry = (Map.Entry) ite1.next();
            String userId = (String) entry.getKey();
            String money = (String) entry.getValue();

            List<Payment> payments = paymentRepository.findByUserId(userId);
            Iterator<Payment> iterator = payments.iterator();
            String totalExpand = "0";
            while (iterator.hasNext()) {
                Payment p = iterator.next();
                BigDecimal expand = new BigDecimal(totalExpand);
                totalExpand = expand.add(new BigDecimal(p.getPrice())).toString();
            }
            String balanceMoney = new BigDecimal(money).subtract(new BigDecimal(totalExpand)).toString();
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setBalance(balanceMoney);
            result.add(balance);
        }

        return new Response<>(1, "Success", result);
    }

    public String queryAccount(String userId) {
        List<Payment> payments = paymentRepository.findByUserId(userId);
        List<Money> addMonies = addMoneyRepository.findByUserId(userId);
        Iterator<Payment> paymentsIterator = payments.iterator();
        Iterator<Money> addMoniesIterator = addMonies.iterator();

        BigDecimal totalExpand = new BigDecimal("0");
        while (paymentsIterator.hasNext()) {
            Payment p = paymentsIterator.next();
            totalExpand.add(new BigDecimal(p.getPrice()));
        }

        BigDecimal money = new BigDecimal("0");
        while (addMoniesIterator.hasNext()) {
            Money addMoney = addMoniesIterator.next();
            money.add(new BigDecimal(addMoney.getMoney()));
        }

        return money.subtract(totalExpand).toString();
    }

    @Override
    public Response queryPayment(HttpHeaders headers) {
        List<Payment> payments = paymentRepository.findAll();
        if (payments != null && !payments.isEmpty()) {
            return new Response<>(1, "Query Payment Success", payments);
        }else {
            LOGGER.error("Query payment failed");
            return new Response<>(0, "Query Payment Failed", null);
        }
    }

    @Override
    public Response drawBack(String userId, String money, HttpHeaders headers) {
        if (addMoneyRepository.findByUserId(userId) != null) {
            Money addMoney = new Money();
            addMoney.setUserId(userId);
            addMoney.setMoney(money);
            addMoney.setType(MoneyType.D);
            addMoneyRepository.save(addMoney);
            return new Response<>(1, "Draw Back Money Success", null);
        } else {
            LOGGER.error("Draw Back Money Failed");
            return new Response<>(0, "Draw Back Money Failed", null);
        }
    }

    @Override
    public Response payDifference(PaymentInfo info, HttpHeaders headers) {

        String userId = info.getUserId();

        Payment payment = new Payment();
        payment.setOrderId(info.getOrderId());
        payment.setPrice(info.getPrice());
        payment.setUserId(info.getUserId());


        List<Payment> payments = paymentRepository.findByUserId(userId);
        List<Money> addMonies = addMoneyRepository.findByUserId(userId);
        Iterator<Payment> paymentsIterator = payments.iterator();
        Iterator<Money> addMoniesIterator = addMonies.iterator();

        BigDecimal totalExpand = new BigDecimal("0");
        while (paymentsIterator.hasNext()) {
            Payment p = paymentsIterator.next();
            totalExpand.add(new BigDecimal(p.getPrice()));
        }
        totalExpand.add(new BigDecimal(info.getPrice()));

        BigDecimal money = new BigDecimal("0");
        while (addMoniesIterator.hasNext()) {
            Money addMoney = addMoniesIterator.next();
            money.add(new BigDecimal(addMoney.getMoney()));
        }

        if (totalExpand.compareTo(money) > 0) {
            //站外支付
            Payment outsidePaymentInfo = new Payment();
            outsidePaymentInfo.setOrderId(info.getOrderId());
            outsidePaymentInfo.setUserId(userId);
            outsidePaymentInfo.setPrice(info.getPrice());

            HttpEntity requestEntityOutsidePaySuccess = new HttpEntity(outsidePaymentInfo, headers);
            ResponseEntity<Response> reOutsidePaySuccess = restTemplate.exchange(
                    "http://ts-payment-service:19001/api/v1/paymentservice/payment",
                    HttpMethod.POST,
                    requestEntityOutsidePaySuccess,
                    Response.class);
            Response outsidePaySuccess = reOutsidePaySuccess.getBody();

            if (outsidePaySuccess.getStatus() == 1) {
                payment.setType(PaymentType.E);
                paymentRepository.save(payment);
                return new Response<>(1, "Pay Difference Success", null);
            } else {
                LOGGER.error("Pay Difference Failed, orderId: {}", info.getOrderId());
                return new Response<>(0, "Pay Difference Failed", null);
            }
        } else {
            payment.setType(PaymentType.E);
            paymentRepository.save(payment);
        }
        return new Response<>(1, "Pay Difference Success", null);
    }

    @Override
    public Response queryAddMoney(HttpHeaders headers) {
        List<Money> monies = addMoneyRepository.findAll();
        if (monies != null && !monies.isEmpty()) {
            return new Response<>(1, "Query Money Success", null);
        } else {
            LOGGER.error("Query money failed");
            return new Response<>(0, "Query money failed", null);
        }
    }

    private Response setOrderStatus(String tripId, String orderId, HttpHeaders headers) {

        //order paid and not collected
        int orderStatus = 1;
        Response result;
        if (tripId.startsWith("G") || tripId.startsWith("D")) {

            HttpEntity requestEntityModifyOrderStatusResult = new HttpEntity(headers);
            ResponseEntity<Response> reModifyOrderStatusResult = restTemplate.exchange(
                    "http://ts-order-service:12031/api/v1/orderservice/order/status/" + orderId + "/" + orderStatus,
                    HttpMethod.GET,
                    requestEntityModifyOrderStatusResult,
                    Response.class);
            result = reModifyOrderStatusResult.getBody();

        } else {
            HttpEntity requestEntityModifyOrderStatusResult = new HttpEntity(headers);
            ResponseEntity<Response> reModifyOrderStatusResult = restTemplate.exchange(
                    "http://ts-order-other-service:12032/api/v1/orderOtherService/orderOther/status/" + orderId + "/" + orderStatus,
                    HttpMethod.GET,
                    requestEntityModifyOrderStatusResult,
                    Response.class);
            result = reModifyOrderStatusResult.getBody();

        }
        return result;
    }

    @Override
    public void initPayment(Payment payment, HttpHeaders headers) {
        Payment paymentTemp = paymentRepository.findById(payment.getId());
        if (paymentTemp == null) {
            paymentRepository.save(payment);
        } else {
            InsidePaymentServiceImpl.LOGGER.error("[Init Payment] Already Exists, paymentId: {}, orderId: {}", payment.getId(), payment.getOrderId());
        }
    }

}

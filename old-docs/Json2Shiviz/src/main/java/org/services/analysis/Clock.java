package org.services.analysis;

import java.util.HashMap;

/**
 * Created by Administrator on 2017/7/18.
 */
public class Clock {

    private String type;
    private String traceId;
    private String spanId;

    private HashMap<String,Integer> clock;

    public Clock(String type, String traceId, String spanId, HashMap<String,Integer> clock) {
        this.type = type;
        this.traceId = traceId;
        this.spanId = spanId;
        this.clock = clock;
    }

    public HashMap<String,Integer> getClock() {
        return clock;
    }

    public boolean isSrc(String traceId, String spanId, String type, String queue, String parentId){
        boolean result = false;

        if("queue".equals(queue)){
            if(traceId.equals(this.traceId) && parentId.equals(this.spanId)){
                if(type.equals("sr") && this.type.equals("cs")){
                    result = true;
                }
            }
        }else{
            if(traceId.equals(this.traceId) && spanId.equals(this.spanId)){
                if(type.equals("sr") && this.type.equals("cs")){
                    result = true;
                }else if(type.equals("cr") && this.type.equals("ss")){
                    result = true;
                }
            }
        }


        return result;
    }
}

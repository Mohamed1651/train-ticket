package travel2.entity;

import java.util.Date;

public class TripRequestContext {
    private String startingPlaceId;
    private String endPlaceId;
    private String startingPlaceName;
    private String endPlaceName;
    private Date departureTime;

    // No-argument constructor
    public TripRequestContext() {}

    // All-argument constructor
    public TripRequestContext(String startingPlaceId, String endPlaceId,
                              String startingPlaceName, String endPlaceName, Date departureTime) {
        this.startingPlaceId = startingPlaceId;
        this.endPlaceId = endPlaceId;
        this.startingPlaceName = startingPlaceName;
        this.endPlaceName = endPlaceName;
        this.departureTime = departureTime;
    }
    public String getStartingPlaceId() { return startingPlaceId; }
    public void setStartingPlaceId(String startingPlaceId) { this.startingPlaceId = startingPlaceId; }

    public String getEndPlaceId() { return endPlaceId; }
    public void setEndPlaceId(String endPlaceId) { this.endPlaceId = endPlaceId; }

    public String getStartingPlaceName() { return startingPlaceName; }
    public void setStartingPlaceName(String startingPlaceName) { this.startingPlaceName = startingPlaceName; }

    public String getEndPlaceName() { return endPlaceName; }
    public void setEndPlaceName(String endPlaceName) { this.endPlaceName = endPlaceName; }

    public Date getDepartureTime() { return departureTime; }
    public void setDepartureTime(Date departureTime) { this.departureTime = departureTime; }
}

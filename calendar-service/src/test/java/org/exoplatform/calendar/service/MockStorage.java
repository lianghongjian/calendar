package org.exoplatform.calendar.service;

import java.util.HashMap;
import java.util.List;

import org.exoplatform.calendar.model.Event;
import org.exoplatform.calendar.model.query.CalendarQuery;
import org.exoplatform.calendar.storage.CalendarDAO;
import org.exoplatform.calendar.storage.EventDAO;
import org.exoplatform.calendar.storage.Storage;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.container.component.BaseComponentPlugin;

public class MockStorage extends BaseComponentPlugin implements Storage {

  public static final String ID = "MOCK_STORAGE";

  CalendarDAO calDAO;

  EventDAO eventDAO;

  @Override
  public String getId() {
    return ID;
  }
    
  @Override
  public CalendarDAO getCalendarDAO() {
    if (calDAO == null) {
      calDAO = new MockCalendarDAO();
    }
    return calDAO;
  }

  @Override
  public EventDAO getEventDAO() {
    if (eventDAO == null) {
      eventDAO = new MockEventDAO();
    }
    return eventDAO;
  }

  class MockCalendarDAO implements CalendarDAO {

    private HashMap<String, org.exoplatform.calendar.model.Calendar> cals = new HashMap<String, org.exoplatform.calendar.model.Calendar>();

    @Override
    public org.exoplatform.calendar.model.Calendar getById(String id) {
      return cals.get(id);
    }

    @Override
    public org.exoplatform.calendar.model.Calendar save(org.exoplatform.calendar.model.Calendar cal) {
      cals.put(cal.getId(), cal);
      return cal;
    }

    @Override
    public org.exoplatform.calendar.model.Calendar remove(String id) {
      return cals.remove(id);
    }

    @Override
    public Calendar newInstance() {
      return new MockCalendar();
    }

    @Override
    public List<org.exoplatform.calendar.model.Calendar> findCalendars(CalendarQuery query) {
      return null;
    }

    @Override
    public org.exoplatform.calendar.model.Calendar update(org.exoplatform.calendar.model.Calendar entity) {
      return null;
    }
  }

  class MockEventDAO implements EventDAO {

    @Override
    public Event getById(String id) {
      return null;
    }

    @Override
    public Event save(Event object) {
      return null;
    }

    @Override
    public Event remove(String id) {
      return null;
    }

    @Override
    public Event newInstance() {
      return null;
    }

    @Override
    public ListAccess<Event> findEventsByQuery(org.exoplatform.calendar.model.query.EventQuery eventQuery) throws CalendarException {
      return null;
    }

    @Override
    public Event update(Event entity) {
      return null;
    }
  }
}

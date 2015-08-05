/**
 * Copyright (C) 2003-2007 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 **/
package org.exoplatform.calendar.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;

import net.fortuna.ical4j.model.DateList;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Recur;

import org.picocontainer.Startable;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.impl.triggers.SimpleTriggerImpl;

import org.exoplatform.calendar.service.Attachment;
import org.exoplatform.calendar.service.Calendar;
import org.exoplatform.calendar.service.CalendarCollection;
import org.exoplatform.calendar.service.CalendarEvent;
import org.exoplatform.calendar.service.CalendarHandler;
import org.exoplatform.calendar.service.CalendarImportExport;
import org.exoplatform.calendar.service.CalendarService;
import org.exoplatform.calendar.service.CalendarSetting;
import org.exoplatform.calendar.service.CalendarType;
import org.exoplatform.calendar.service.CalendarUpdateEventListener;
import org.exoplatform.calendar.service.Constants;
import org.exoplatform.calendar.service.EventCategory;
import org.exoplatform.calendar.service.EventHandler;
import org.exoplatform.calendar.service.EventQuery;
import org.exoplatform.calendar.service.FeedData;
import org.exoplatform.calendar.service.ImportCalendarJob;
import org.exoplatform.calendar.service.RemoteCalendar;
import org.exoplatform.calendar.service.RemoteCalendarService;
import org.exoplatform.calendar.service.RssData;
import org.exoplatform.calendar.service.SynchronizeRemoteCalendarJob;
import org.exoplatform.calendar.service.Utils;
import org.exoplatform.calendar.service.storage.CalendarDAO;
import org.exoplatform.calendar.service.storage.EventDAO;
import org.exoplatform.calendar.service.storage.Storage;
import org.exoplatform.calendar.service.storage.jcr.JCRStorage;
import org.exoplatform.commons.utils.ExoProperties;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.services.cache.CacheService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.exoplatform.services.jcr.util.IdGenerator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;
import org.exoplatform.services.scheduler.impl.JobSchedulerServiceImpl;


/**
 * Created by The eXo Platform SARL Author : Hung Nguyen Quang
 * hung.nguyen@exoplatform.com Jul 11, 2007
 */
public class CalendarServiceImpl extends LegacyCalendarServiceImpl implements CalendarService, Startable {

  private final AtomicBoolean                 isRBLoaded_           = new AtomicBoolean(); 

  private ResourceBundle                      rb_;

  private ResourceBundleService               rbs_;

  private Map<String, CalendarImportExport>   calendarImportExport_ = new LinkedHashMap<String, CalendarImportExport>();

  protected List<CalendarUpdateEventListener> listeners_            = new ArrayList<CalendarUpdateEventListener>(3);

  private RemoteCalendarService               remoteCalendarService;

  private static final Log LOG = ExoLogger.getExoLogger(CalendarServiceImpl.class);  
  
  private Map<String, Storage> storages = new HashMap<String, Storage>();
  
  private CalendarHandler calendarHandler;

  private EventHandler eventHandler;  

  public CalendarServiceImpl(InitParams params, NodeHierarchyCreator nodeHierarchyCreator, RepositoryService reposervice, ResourceBundleService rbs, CacheService cservice) throws Exception {
    super(nodeHierarchyCreator, reposervice, cservice);
    
    this.calendarHandler = new CalendarHandlerImpl(this);
    this.eventHandler = new EventHandlerImpl(this);
    
    calendarImportExport_.put(CalendarService.ICALENDAR, new ICalendarImportExport(storage_));
    calendarImportExport_.put(CalendarService.EXPORTEDCSV, new CsvImportExport(storage_));
    remoteCalendarService = new RemoteCalendarServiceImpl(storage_);    
    rbs_ = rbs;    
    ExoProperties props = params.getPropertiesParam("eventNumber.info").getProperties();
    String eventNumber = props.getProperty("eventNumber");
    Utils.EVENT_NUMBER = Integer.parseInt(eventNumber);
  }

  /**
   * {@inheritDoc}
   */
  public List<EventCategory> getEventCategories(String username) throws Exception {
    return storage_.getEventCategories(username);
  }
  
  public CalendarCollection<EventCategory> getEventCategories(String username, int offset, int limit) throws Exception {
    if (username == null) {
      throw new IllegalArgumentException("username must not null");
    }
    List<EventCategory> categories = storage_.getEventCategories(username);
    int fullSize = categories.size();
    
    return new CalendarCollection<EventCategory>(Utils.subList(categories, offset, limit), fullSize);
  }

  /**
   * {@inheritDoc}
   */
  public void saveEventCategory(String username, EventCategory eventCategory, boolean isNew) throws Exception {
    EventCategory ev = getEventCategoryByName(username, eventCategory.getName());
    if (ev != null && (isNew || !ev.getId().equals(eventCategory.getId())))
      throw new ItemExistsException();
    storage_.saveEventCategory(username, eventCategory, isNew);
  }

  /**
   * {@inheritDoc}
   */
  public void saveEventCategory(String username, EventCategory eventCategory, String[] values, boolean isNew) throws Exception {
    saveEventCategory(username, eventCategory, isNew);
  }

  /**
   * {@inheritDoc}
   */
  public void removeEventCategory(String username, String eventCategoryId) throws Exception {
    storage_.removeEventCategory(username, eventCategoryId);
  }

  /**
   * {@inheritDoc}
   */
  public CalendarImportExport getCalendarImportExports(String type) {
    return calendarImportExport_.get(type);
  }

  /**
   * {@inheritDoc}
   */
  public String[] getExportImportType() throws Exception {
    return calendarImportExport_.keySet().toArray(new String[]{});
  }

  /**
   * {@inheritDoc}
   */
  public void saveCalendarSetting(String username, CalendarSetting setting) throws Exception {
    storage_.saveCalendarSetting(username, setting);
  }

  /**
   * {@inheritDoc}
   */
  public CalendarSetting getCalendarSetting(String username) throws Exception {
    return storage_.getCalendarSetting(username);
  }

  /**
   * {@inheritDoc}
   */
  public int generateRss(String username, LinkedHashMap<String, Calendar> calendars, RssData rssData) throws Exception {
    return storage_.generateRss(username, calendars, rssData, calendarImportExport_.get(CalendarService.ICALENDAR));
  }

  /**
   * {@inheritDoc}
   */
  public List<FeedData> getFeeds(String username) throws Exception {
    return storage_.getFeeds(username);
  }

  /**
   * {@inheritDoc}
   */
  public Node getRssHome(String username) throws Exception {
    return storage_.getRssHome(username);
  }

  /**
   * {@inheritDoc}
   */
  public EventCategory getEventCategory(String username, String eventCategoryId) throws Exception {
    return storage_.getEventCategory(username, eventCategoryId);
  }

  /**
   * {@inheritDoc}
   */
  public Map<Integer, String> searchHightLightEvent(String username, EventQuery eventQuery, String[] publicCalendarIds) throws Exception {
    return storage_.searchHightLightEvent(username, eventQuery, publicCalendarIds);
  }

  public List<Map<Integer, String>> searchHightLightEventSQL(String username, EventQuery eventQuery,
                                                             String[] privateCalendars, String[] publicCalendars) throws Exception {
    return storage_.searchHightLightEventSQL(username, eventQuery, privateCalendars, publicCalendars);
  }

  @Override
  public List<CalendarEvent> getAllNoRepeatEvents(String username, EventQuery eventQuery, String[] publicCalendarIds) throws Exception {
    return storage_.getAllNoRepeatEvents(username, eventQuery, publicCalendarIds);
  }

  /**
   * {@inheritDoc}
   */
  public List<CalendarEvent> getAllNoRepeatEventsSQL(String username, EventQuery eventQuery, String[] privateCalendars,
                                                     String[] publicCalendars, List<String> emptyCalendars) throws Exception {
    return storage_.getAllNoRepeatEventsSQL(username, eventQuery, privateCalendars, publicCalendars, emptyCalendars);
  }

  /**
   * {@inheritDoc}
   */
  public Map<String, String> checkFreeBusy(EventQuery eventQuery) throws Exception {
    return storage_.checkFreeBusy(eventQuery);
  }

  /**
   * {@inheritDoc}
   */
  public void moveEvent(String fromCalendar, String toCalendar, String fromType, String toType, List<CalendarEvent> calEvents, String username) throws Exception {
    Map<String,  CalendarEvent> oldEventList = new HashMap<String, CalendarEvent>();
    if (fromType.equalsIgnoreCase(toType) && toType.equalsIgnoreCase(String.valueOf(Calendar.TYPE_PUBLIC)) && fromCalendar.equalsIgnoreCase(toCalendar)) {
      for (CalendarEvent event : calEvents) {
        oldEventList.put(event.getId(), getGroupEvent(event.getId()));
      }
    }
    storage_.moveEvent(fromCalendar, toCalendar, fromType, toType, calEvents, username);

    if (fromType.equalsIgnoreCase(toType) && toType.equalsIgnoreCase(String.valueOf(Calendar.TYPE_PUBLIC)) && fromCalendar.equalsIgnoreCase(toCalendar)) {
      for (CalendarEventListener cel : eventListeners_) {
        for (CalendarEvent event : calEvents) {
          if (!oldEventList.isEmpty() && oldEventList.get(event.getId()) != null) {
            cel.updatePublicEvent(oldEventList.get(event.getId()), event, toCalendar);
            storage_.savePublicEvent(toCalendar, event, false) ;
          }
        }
      }
    }

    if (!fromType.equalsIgnoreCase(String.valueOf(Calendar.TYPE_PUBLIC)) && toType.equalsIgnoreCase(String.valueOf(Calendar.TYPE_PUBLIC)) ) {
      for(CalendarEventListener cel : eventListeners_) {
        for (CalendarEvent event : calEvents) {
          cel.savePublicEvent(event, toCalendar);
          storage_.savePublicEvent(toCalendar, event, false) ;
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public void confirmInvitation(String fromUserId, String toUserId, int calType, String calendarId, String eventId, int answer) {
    storage_.confirmInvitation(fromUserId, toUserId, calType, calendarId, eventId, answer);
  }

  /**
   * {@inheritDoc}
   */
  public void confirmInvitation(String fromUserId, String confirmingEmail, String confirmingUser, int calType, String calendarId, String eventId, int answer) throws Exception {
    storage_.confirmInvitation(fromUserId, confirmingEmail, confirmingUser, calType, calendarId, eventId, answer);
  }

  public void start() {
    for (CalendarUpdateEventListener updateListener : listeners_) {
      updateListener.preUpdate();
    }
  }

  public void stop() {
  }

  /**
   * {@inheritDoc}
   */
  public synchronized void addListenerPlugin(CalendarUpdateEventListener listener) throws Exception {
    listeners_.add(listener);
  }

  public int generateRss(String username, List<String> calendarIds, RssData rssData) throws Exception {
    return storage_.generateRss(username, calendarIds, rssData, calendarImportExport_.get(CalendarService.ICALENDAR));
  }

  public ResourceBundle getResourceBundle() throws Exception {
    if (!isRBLoaded_.get()) {
      synchronized (isRBLoaded_) {
        if (!isRBLoaded_.get()) {
          try {
            rb_ = rbs_.getResourceBundle(Utils.RESOURCEBUNDLE_NAME, Locale.getDefault());
          } catch (MissingResourceException e) {
            rb_ = null;
          }
          isRBLoaded_.set(true);
        }
      }
    } 
    return rb_;
  }

  public EventCategory getEventCategoryByName(String username, String eventCategoryName) throws Exception {
    for (EventCategory ev : storage_.getEventCategories(username)) {
      if (ev.getName().equalsIgnoreCase(eventCategoryName)) {
        return ev;
      }
    }
    return null;
  }

  public void removeFeedData(String username, String title) {
    storage_.removeFeedData(username, title);
  }

  public void initNewUser(String userName, CalendarSetting defaultCalendarSetting_) throws Exception {
    EventCategory eventCategory = new EventCategory();
    eventCategory.setDataInit(true);
    for (int id = 0; id < NewUserListener.defaultEventCategoryIds.length; id++) {
      if (NewUserListener.DEFAULT_EVENTCATEGORY_ID_ALL.equals(id)) continue;
      String savingCategoryName = NewUserListener.defaultEventCategoryNames[id];
      if (getEventCategoryByName(userName, savingCategoryName) != null) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Calendar data for " + userName + " already exist.");
        }
        return;
      }
      eventCategory.setId(NewUserListener.defaultEventCategoryIds[id]);
      eventCategory.setName(savingCategoryName);
      saveEventCategory(userName, eventCategory, true);
    }

    // get the user's full name
    OrganizationService organizationService = (OrganizationService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(OrganizationService.class);
    User u = organizationService.getUserHandler().findUserByName(userName);
    String fullName = u.getFirstName();
    if (u.getLastName() != null && fullName != null) {
      fullName = new StringBuilder().append(fullName).append(" ").append(u.getLastName()).toString();
    }
    if (fullName == null) fullName = u.getUserName();
    // save default calendar
    Calendar cal = new Calendar();
    cal.setId(Utils.getDefaultCalendarId(userName));
    cal.setName(fullName); // name the default calendar after the user's full name, cf CAL-86
    cal.setDataInit(true);
    cal.setCalendarOwner(userName);
    cal.setCalendarColor(Constants.COLORS[0]);
    if (defaultCalendarSetting_ != null) {
      if (defaultCalendarSetting_.getTimeZone() != null)
        cal.setTimeZone(defaultCalendarSetting_.getTimeZone());
    }
    saveUserCalendar(userName, cal, true);

    if (defaultCalendarSetting_ != null) {
      saveCalendarSetting(userName, defaultCalendarSetting_);
    }

    Object[] groupsOfUser = organizationService.getGroupHandler().findGroupsOfUser(userName).toArray();
    List<String> groups = new ArrayList<String>();
    for (Object object : groupsOfUser) {
      String groupId = ((Group) object).getId();
      groups.add(groupId);
    }
    storage_.autoShareCalendar(groups, userName);
  }

  public void addEventListenerPlugin(CalendarEventListener listener) throws Exception {    
    eventListeners_.add(listener);
  }

  /**
   * {@inheritDoc}
   */
  public boolean isValidRemoteUrl(String url, String type, String remoteUser, String remotePassword) throws Exception {
    return remoteCalendarService.isValidRemoteUrl(url, type, remoteUser, remotePassword);
  }

  /**
   * {@inheritDoc}
   */
  public Calendar updateRemoteCalendarInfo(RemoteCalendar remoteCalendar) throws Exception {
    return storage_.updateRemoteCalendarInfo(remoteCalendar);
  }

  /**
   * {@inheritDoc}
   */
  public Calendar refreshRemoteCalendar(String username, String remoteCalendarId) throws Exception {
    return remoteCalendarService.refreshRemoteCalendar(username, remoteCalendarId);
  }

  /**
   * {@inheritDoc}
   */
  public Calendar importRemoteCalendar(RemoteCalendar remoteCalendar) throws Exception {
    return remoteCalendarService.importRemoteCalendar(remoteCalendar);
  }

  /**
   * {@inheritDoc}
   */
  public RemoteCalendar getRemoteCalendar(String owner, String calendarId) throws Exception {
    return storage_.getRemoteCalendar(owner, calendarId);
  }

  /**
   * {@inheritDoc}
   */
  public RemoteCalendarService getRemoteCalendarService() throws Exception {
    return remoteCalendarService;
  }

  public Calendar getRemoteCalendar(String owner, String remoteUrl, String remoteType) throws Exception {
    return storage_.getRemoteCalendar(owner, remoteUrl, remoteType);
  }

  public String getCalDavResourceHref(String username, String calendarId, String eventId) throws Exception {
    Node eventNode = storage_.getUserCalendarHome(username).getNode(calendarId).getNode(eventId);
    try {
      return eventNode.getProperty(Utils.EXO_CALDAV_HREF).getString();
    } catch (PathNotFoundException e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Exception when getting caldav resource",e);
      }
      return null;
    }
  }

  public String getCalDavResourceEtag(String username, String calendarId, String eventId) throws Exception {
    Node eventNode = storage_.getUserCalendarHome(username).getNode(calendarId).getNode(eventId);
    return eventNode.getProperty(Utils.EXO_CALDAV_ETAG).getString();
  }

  public void setCalDavResourceHref(String username, String calendarId, String eventId, String href) throws Exception {
    storage_.setCalDavResourceHref(username, calendarId, eventId, href);
  }

  public void setCalDavResourceEtag(String username, String calendarId, String eventId, String etag) throws Exception {
    storage_.setCalDavResourceEtag(username, calendarId, eventId, etag);
  }

  // load synchronize remote calendar job with period
  public void loadSynchronizeRemoteCalendarJob(String username) throws Exception {
    ExoContainer container = ExoContainerContext.getCurrentContainer();
    JobSchedulerService schedulerService = (JobSchedulerService) container.getComponentInstanceOfType(JobSchedulerService.class);
    JobInfo info = SynchronizeRemoteCalendarJob.getJobInfo(username);
    JobDetail job = findSynchronizeRemoteCalendarJob(schedulerService, username);
    if (job == null) {
      JobDataMap jobData = new JobDataMap();
      jobData.put(SynchronizeRemoteCalendarJob.USERNAME, username);
      RepositoryService repositoryService = (RepositoryService) container.getComponentInstanceOfType(RepositoryService.class);
      jobData.put(repositoryService.getCurrentRepository()
                  .getConfiguration()
                  .getName(), repositoryService.getCurrentRepository()
                  .getConfiguration()
                  .getName());
      PeriodInfo periodInfo = new PeriodInfo(null, null, 0, 5 * 60 * 1000);
      schedulerService.addPeriodJob(info, periodInfo, jobData);
    }
  }

  public void stopSynchronizeRemoteCalendarJob(String username) throws Exception {
    JobInfo info = SynchronizeRemoteCalendarJob.getJobInfo(username);
    ExoContainer container = ExoContainerContext.getCurrentContainer();
    JobSchedulerService schedulerService = (JobSchedulerService) container.getComponentInstanceOfType(JobSchedulerService.class);
    schedulerService.removeJob(info);
    schedulerService.removeJobListener(info.getJobName());
    schedulerService.removeTriggerListener(info.getJobName());
  }

  public JobDetail findSynchronizeRemoteCalendarJob(JobSchedulerService schedulerService, String username) throws Exception {
    // find synchronize job
    List<JobDetail> list = schedulerService.getAllJobs();
    for (JobDetail jobDetail : list) {
      if (jobDetail.getKey().getName().equals(SynchronizeRemoteCalendarJob.getRemoteCalendarName(username))) {
        return jobDetail;
      }
    }
    return null;
  }

  public int getRemoteCalendarCount(String username) throws Exception {
    return storage_.getRemoteCalendarCount(username);
  }

  /*
   * (non-Javadoc)
   * @see org.exoplatform.calendar.service.CalendarService#getOccurrenceEvents(org.exoplatform.calendar.service.CalendarEvent, java.util.Calendar, java.util.Calendar)
   */
  public Map<String, CalendarEvent> getOccurrenceEvents(CalendarEvent recurEvent, java.util.Calendar from, java.util.Calendar to, String timezone) throws Exception {
    return storage_.getOccurrenceEvents(recurEvent, from, to, timezone);
  }

  /*
   * (non-Javadoc)
   * @see org.exoplatform.calendar.service.CalendarService#updateOccurrenceEvent(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.util.List, java.lang.String)
   */
  public void updateOccurrenceEvent(String fromCalendar, String toCalendar, String fromType, String toType, List<CalendarEvent> calEvents, String username) throws Exception {
    // keep map of old events to compare
    Map<String,  CalendarEvent> oldEventList = new HashMap<String, CalendarEvent>();
    if (fromType.equalsIgnoreCase(toType) && toType.equalsIgnoreCase(String.valueOf(Calendar.TYPE_PUBLIC)) && fromCalendar.equalsIgnoreCase(toCalendar)) {
      for (CalendarEvent event : calEvents) {
        oldEventList.put(event.getId(), getGroupEvent(event.getId()));
      }
    }
    storage_.updateOccurrenceEvent(fromCalendar, toCalendar, fromType, toType, calEvents, username);
    if (fromType.equalsIgnoreCase(toType) && toType.equalsIgnoreCase(String.valueOf(Calendar.TYPE_PUBLIC)) && fromCalendar.equalsIgnoreCase(toCalendar)) {
      for (CalendarEventListener cel : eventListeners_) {
        for (CalendarEvent event : calEvents) {
          // if event existed, update the activity
          if (oldEventList.get(event.getId()) != null) {
            cel.updatePublicEvent(oldEventList.get(event.getId()), event, toCalendar);
            storage_.savePublicEvent(toCalendar, event, false) ;
          } else {
            // publish new activity
            cel.savePublicEvent(event, toCalendar);
            storage_.savePublicEvent(toCalendar, event, false);
          }
        }
      }
    }
  }

  public List<CalendarEvent> getOriginalRecurrenceEvents(String username, java.util.Calendar from, java.util.Calendar to, String[] publicCalendarIds) throws Exception {
    return storage_.getOriginalRecurrenceEvents(username, from, to, publicCalendarIds);
  }

  /*
   * (non-Javadoc)
   * @see org.exoplatform.calendar.service.CalendarService#getExceptionEvents(org.exoplatform.calendar.service.CalendarEvent)
   */
  public List<CalendarEvent> getExceptionEvents(String username, CalendarEvent recurEvent) throws Exception {
    return storage_.getExceptionEvents(username, recurEvent);
  }

  /*
   * (non-Javadoc)
   * @see org.exoplatform.calendar.service.CalendarService#removeRecurrenceSeries(java.lang.String, org.exoplatform.calendar.service.CalendarEvent)
   */
  public void removeRecurrenceSeries(String username, CalendarEvent originalEvent) throws Exception {
    storage_.removeRecurrenceSeries(username, originalEvent);
    if(originalEvent.getActivityId() != null) {
      for (CalendarEventListener cel : eventListeners_) {
        cel.deletePublicEvent(originalEvent, originalEvent.getCalendarId());
      }
    }
  }

  /*
   * (non-Javadoc)
   * @see org.exoplatform.calendar.service.CalendarService#updateRecurrenceSeries(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.util.List, java.lang.String)
   */
  public void updateRecurrenceSeries(String fromCalendar, String toCalendar, String fromType, String toType, CalendarEvent occurrence, String username) throws Exception {
    CalendarEvent oldEvent = getGroupEvent(occurrence.getId());
    if (oldEvent == null) oldEvent = getEvent(username, occurrence.getId());
    storage_.updateRecurrenceSeries(fromCalendar, toCalendar, fromType, toType, occurrence, username);
    if(toType.equalsIgnoreCase(String.valueOf(Utils.PUBLIC_TYPE))) {
      for (CalendarEventListener cel : eventListeners_) {
        if(oldEvent != null) {
          if (oldEvent.getActivityId() != null)
          {
            occurrence.setActivityId(oldEvent.getActivityId());
            cel.updatePublicEvent(oldEvent, occurrence, fromCalendar);
          }
          else {
            cel.updatePublicEvent(occurrence, toCalendar);
          }
          storage_.savePublicEvent(toCalendar, occurrence, false) ;
        }
      }
    }

  }

  /*
   * (non-Javadoc)
   * @see org.exoplatform.calendar.service.CalendarService#removeOccurrenceInstance(java.lang.String, org.exoplatform.calendar.service.CalendarEvent)
   */
  public void removeOccurrenceInstance(String username, CalendarEvent occurrence) throws Exception {
    storage_.removeOccurrenceInstance(username, occurrence);

    /*=== check if we remove the last instance of repetitive event ===*/
    String calendarId = occurrence.getCalendarId();
    int calType = Integer.parseInt(occurrence.getCalType());
    CalendarEvent repetitiveEvent = null;

    // get the original repetitive event
    if (calType == Calendar.TYPE_PRIVATE)
      repetitiveEvent = storage_.getUserEvent(username, calendarId, occurrence.getId());
    else if (calType == Calendar.TYPE_PUBLIC)
      repetitiveEvent = storage_.getGroupEvent(calendarId, occurrence.getId());
    else if (calType == Calendar.TYPE_SHARED)
      repetitiveEvent = storage_.getSharedEvent(username, calendarId, occurrence.getId());

    if (isEventSeriesGettingToLastItem(repetitiveEvent, username))
    {
      /* remove the original repetitive event */
      removeRecurrenceSeries(username, repetitiveEvent);
      return ;
    }

    if(occurrence.getActivityId() != null) {
      for (CalendarEventListener cel : eventListeners_) {
        cel.deletePublicEvent(occurrence, occurrence.getCalendarId());
      }  
    }
    else /* remove an instance of repetitive event */
    {
      /* reset date for occurrence to avoids posting changed date */
      occurrence.setFromDateTime(repetitiveEvent.getFromDateTime());
      occurrence.setToDateTime(repetitiveEvent.getToDateTime());

      for (CalendarEventListener cel : eventListeners_) {
        cel.updatePublicEvent(occurrence, repetitiveEvent, repetitiveEvent.getCalendarId());
      }
    }

  }

  /**
   * check if the series of event is getting to its last item
   *
   * @param repetitiveEvent
   * @return
   */
  private boolean isEventSeriesGettingToLastItem(CalendarEvent repetitiveEvent, String username) throws Exception
  {
    /* event repeated unlimited  */
    if ((repetitiveEvent.getRepeatCount() <= 0) && (repetitiveEvent.getRepeatUntilDate() == null)) return false;

    /* event repeated to a certain date */
    if ((repetitiveEvent.getRepeatCount() <= 0) && (repetitiveEvent.getRepeatUntilDate() != null))
    {
      CalendarSetting calendarSetting = getCalendarSetting(username);
      TimeZone timezone = TimeZone.getTimeZone(calendarSetting.getTimeZone()); // europe brussels
      java.util.Calendar fromDate = java.util.Calendar.getInstance(timezone);
      fromDate.setTime(repetitiveEvent.getFromDateTime());
      java.util.Calendar toDate   = java.util.Calendar.getInstance(timezone);
      toDate.setTime(repetitiveEvent.getRepeatUntilDate()); // correct date

      Map<String, CalendarEvent> occurrenceEvents = getOccurrenceEvents(repetitiveEvent, fromDate, toDate, calendarSetting.getTimeZone()); // not null
      return (occurrenceEvents.keySet().size() == 0);
    }

    /* event with repeat_count set*/
    int numberOfOccurrences = (int) repetitiveEvent.getRepeatCount();
    return (repetitiveEvent.getExceptionIds() != null && repetitiveEvent.getExceptionIds().size() == numberOfOccurrences);
  }

  public Map<Integer, String> searchHighlightRecurrenceEvent(String username, EventQuery eventQuery, String[] publicCalendarIds, String timezone) throws Exception {
    return storage_.searchHighlightRecurrenceEvent(username, eventQuery, publicCalendarIds, timezone);
  }

  public List<Map<Integer, String>> searchHighlightRecurrenceEventSQL(String username, EventQuery eventQuery, String timezone,
                                                                String[] privateCalendars, String[] publicCalendars) throws Exception {
    return storage_.searchHighlightRecurrenceEventSQL(username, eventQuery, timezone, privateCalendars, publicCalendars);
  }

  public List<CalendarEvent> getHighLightOriginalRecurrenceEvents(String username, java.util.Calendar from, java.util.Calendar to, String[] publicCalendarIds) throws Exception {
    return storage_.getHighLightOriginalRecurrenceEvents(username, from, to, publicCalendarIds);
  }

  public List<CalendarEvent> getHighLightOriginalRecurrenceEventsSQL(String username, java.util.Calendar from, java.util.Calendar to, EventQuery eventQuery,
                                                                     String[] privateCalendars, String[] publicCalendars, List<String> emptyCalendars) throws Exception {
    return storage_.getHighLightOriginalRecurrenceEventsSQL(username, from, to, eventQuery, privateCalendars, publicCalendars, emptyCalendars);
  }

  @Override
  public void importRemoteCalendarByJob(RemoteCalendar remoteCalendar) throws Exception {

    JobSchedulerServiceImpl  schedulerService = (JobSchedulerServiceImpl)ExoContainerContext.getCurrentContainer().
        getComponentInstance(JobSchedulerService.class) ;

    JobDetail job = ImportCalendarJob.getImportRemoteCalendarJobDetail(remoteCalendar);

    SimpleTriggerImpl trigger = new SimpleTriggerImpl();
    trigger.setName(remoteCalendar.getCalendarName());
    trigger.setGroup(ImportCalendarJob.IMPORT_CALENDAR_JOB_GROUP_NAME);
    trigger.setStartTime(new Date());

    schedulerService.addJob(job, trigger);  
  }

  @Override
  public void saveOneOccurrenceEvent(CalendarEvent originEvent, CalendarEvent selectedOccurrence,
                                     String username) {
    try {
      //calendar types of origin event and exception event
      int fromType = Integer.parseInt(originEvent.getCalType());
      int toType = Integer.parseInt(selectedOccurrence.getCalType());
      //calendar id of origin event and exception event
      String fromCalendar = originEvent.getCalendarId();
      String toCalendar = selectedOccurrence.getCalendarId();

      //if selectedOccurrence is a new event that is broken from the series
      if (!Utils.isExceptionOccurrence(selectedOccurrence)) {
        if (originEvent != null) {
          //add the recurrence id of the exception event to the excluded list of the origin event
          originEvent.addExceptionId(selectedOccurrence.getRecurrenceId());
          //and then save the updates to the origin event
          if (fromType == Calendar.TYPE_PRIVATE) {
            storage_.saveUserEvent(username, fromCalendar, originEvent, false);
          } else if (fromType == Calendar.TYPE_SHARED) {
            storage_.saveEventToSharedCalendar(username, fromCalendar, originEvent, false);
          } else if (fromType == Calendar.TYPE_PUBLIC) {
            storage_.savePublicEvent(fromCalendar, originEvent, false);
          }
        }
      }

      //the edited exception event is not moved to another calendar
      if (fromCalendar.equals(toCalendar)) {
        if (Utils.isExceptionOccurrence(selectedOccurrence)) {
          storage_.saveOccurrenceEvent(username, toCalendar, selectedOccurrence, false);
        } else {
          storage_.saveOccurrenceEvent(username, toCalendar, selectedOccurrence, true);
          //create activity for the exception event
          if(fromType == Calendar.TYPE_PUBLIC) {
            for(CalendarEventListener cel : eventListeners_) {
              cel.savePublicEvent(selectedOccurrence, selectedOccurrence.getCalendarId());
            }
          }
        }
      } else { //this case is when the exception event is moved to another calendar

        //if it is already an exception event, remove it first
        if (Utils.isExceptionOccurrence(selectedOccurrence)) {
          if (fromType == Calendar.TYPE_PRIVATE) {
            removeUserEvent(username, fromCalendar, selectedOccurrence.getId());
          } else if (fromType == Calendar.TYPE_SHARED) {
            removeSharedEvent(username, fromCalendar, selectedOccurrence.getId());
          } else if (fromType == Calendar.TYPE_PUBLIC) {
            removePublicEvent(fromCalendar, selectedOccurrence.getId());
          }
        }
        //then  save it to the new calendar
        selectedOccurrence.setCalendarId(toCalendar);
        selectedOccurrence.setRepeatType(CalendarEvent.RP_NOREPEAT);
        selectedOccurrence.setIsExceptionOccurrence(false);

        if (toType == Calendar.TYPE_PRIVATE) {
          saveUserEvent(username, toCalendar, selectedOccurrence, true);
        } else if (toType == Calendar.TYPE_SHARED) {
          saveEventToSharedCalendar(username, toCalendar, selectedOccurrence, true);
        } else if (toType == Calendar.TYPE_PUBLIC) {
          savePublicEvent(toCalendar, selectedOccurrence, true);
        }
      }
    } catch (Exception e) {
      LOG.error("Error occurred when updating occurrence event", e);
    }
  }

  @Override
  public void saveAllSeriesEvents(CalendarEvent occurrence,
                                  String username) {
    try {
      String timezone = getCalendarSetting(username).getTimeZone();
      TimeZone tz = TimeZone.getTimeZone(timezone);
      CalendarEvent originEvent = getRepetitiveEvent(occurrence);

      updateOriginFromToTime(originEvent, occurrence);
      fillOriginFromOccurrence(originEvent, occurrence);
      Utils.updateOriginDate(originEvent, tz);

      int fromType = Integer.parseInt(originEvent.getCalType());
      int toType = Integer.parseInt(occurrence.getCalType());


      //List<CalendarEvent> exceptions = getExceptionEvents(username, originEvent);
      //removeEvents(username, exceptions);

      String fromCalendar = originEvent.getCalendarId();
      String toCalendar = occurrence.getCalendarId();

      // if move occurrence to another calendar, same date
      if (!fromCalendar.equals(toCalendar)) {
        // remove original event from old calendar
        switch (fromType) {
        case Calendar.TYPE_PRIVATE:
          removeUserEvent(username, fromCalendar, originEvent.getId());
          break;

        case Calendar.TYPE_PUBLIC:
          removePublicEvent(fromCalendar, originEvent.getId());
          break;

        case Calendar.TYPE_SHARED:
          removeSharedEvent(username, fromCalendar, originEvent.getId());
          break;
        default:
          break;
        }
        // save new original event to new calendar
        CalendarEvent newEvent = new CalendarEvent(originEvent);
        newEvent.setCalendarId(toCalendar);
        newEvent.setExceptionIds(originEvent.getExceptionIds());

        switch (toType) {
        case Calendar.TYPE_PRIVATE:
          saveUserEvent(username, toCalendar, newEvent, false);
          break;

        case Calendar.TYPE_PUBLIC:
          savePublicEvent(toCalendar, newEvent, false);
          break;

        case Calendar.TYPE_SHARED:
          saveEventToSharedCalendar(username, toCalendar, newEvent, false);
          break;
        default:
          break;
        }
      } else {
        // save original event
        switch (fromType) {
        case Calendar.TYPE_PRIVATE:
          saveUserEvent(username, fromCalendar, originEvent, false);
          break;

        case Calendar.TYPE_PUBLIC:
          savePublicEvent(fromCalendar, originEvent, false);
          break;

        case Calendar.TYPE_SHARED:
          saveEventToSharedCalendar(username, fromCalendar, originEvent, false);
          break;
        default:
          break;
        }
      }

    } catch(Exception e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("exception occurs when save all series",e);
      }
    }
  }

  @Override
  public void saveFollowingSeriesEvents(CalendarEvent originEvent, CalendarEvent selectedOccurrence,
                                        String username) {
    try {
      String timezone = getCalendarSetting(username).getTimeZone();
      Date stopDate = Utils.getPreviousOccurrenceDate(originEvent, selectedOccurrence.getFromDateTime(),
              TimeZone.getTimeZone(timezone));
      Boolean isFirstOccurrence = (stopDate == null);

      String calendarId = originEvent.getCalendarId();
      if(!isFirstOccurrence) {
        //
        if (selectedOccurrence.getRepeatUntilDate() == null && selectedOccurrence.getRepeatCount() != 0) {
          Recur recur = Utils.getICalendarRecur(originEvent);
          
          DateTime ical4jEventFrom = new DateTime(originEvent.getFromDateTime());//the date time of the first occurrence of the series
          net.fortuna.ical4j.model.TimeZone tz = Utils.getICalTimeZone(TimeZone.getTimeZone(timezone));
          ical4jEventFrom.setTimeZone(tz);
          
          TimeZone userTimeZone = TimeZone.getTimeZone(timezone);
          SimpleDateFormat format = new SimpleDateFormat(Utils.DATE_FORMAT_RECUR_ID);
          format.setTimeZone(userTimeZone);
          
          Utils.adaptRepeatRule(recur, originEvent.getFromDateTime(), CalendarService.PERSISTED_TIMEZONE, userTimeZone);

          DateTime ical4jFrom = new DateTime(originEvent.getFromDateTime());
          java.util.Calendar toCal = java.util.Calendar.getInstance();
          toCal.setTime(stopDate);
          toCal.add(java.util.Calendar.MINUTE, 5);
          DateTime ical4jTo = new DateTime(toCal.getTime());
          Period period = new Period(ical4jFrom, ical4jTo);
          period.setTimeZone(tz);
          // get list of occurrences in a period
          DateList list = recur.getDates(ical4jEventFrom,
                                         period,
                                         net.fortuna.ical4j.model.parameter.Value.DATE_TIME);
          long count = selectedOccurrence.getRepeatCount();
          selectedOccurrence.setRepeatCount(count - list.size());
        }
        
        //if selected occurrence is not the first occurrence, update the origin event and set new id for
        //the selected occurrence
        originEvent.setRepeatUntilDate(stopDate);
        selectedOccurrence.setId("Event" + IdGenerator.generate());//set new id
      }
      selectedOccurrence.setRecurrenceId(null);
      selectedOccurrence.setExceptionIds(null);
      selectedOccurrence.setExcludeId(null);

      //if the selected occurrence is the first occurrence, save the selected occurrence as if
      //it is the origin event, otherwise update the origin and save new series from the selected occurrence
      switch (Integer.parseInt(originEvent.getCalType())) {
      case Calendar.TYPE_PRIVATE:
        if(isFirstOccurrence) {
          saveUserEvent(username, calendarId, selectedOccurrence, false);
        } else {
          saveUserEvent(username, calendarId, selectedOccurrence, true);
          saveUserEvent(username, calendarId, originEvent, false);
        }
        break;

      case Calendar.TYPE_PUBLIC:
        if(isFirstOccurrence) {
          savePublicEvent(calendarId, selectedOccurrence, false);
        } else {
          //we don't want to add old-content comment to origin event's activity, so we call the method from storage
          storage_.savePublicEvent(calendarId, originEvent, false);
          for(CalendarEventListener listener : eventListeners_) {
            //add comment (with new content format) to the activity of the origin repetitive event

            listener.updateFollowingOccurrences(originEvent, stopDate);
          }
          savePublicEvent(calendarId, selectedOccurrence, true);//publish event to create activity
        }
        break;

      case Calendar.TYPE_SHARED:
        if(isFirstOccurrence) {
          saveEventToSharedCalendar(username, calendarId, selectedOccurrence, false);
        } else {
          saveEventToSharedCalendar(username, calendarId, originEvent, false);
          saveEventToSharedCalendar(username, calendarId, selectedOccurrence, true);
        }
        break;
      default:
        break;
      }
      //get all exception events in the future and remove them
      List<CalendarEvent> exceptionEvents = getExceptionEventsFromDate(username, originEvent, selectedOccurrence.getFromDateTime());
      removeEvents(username, exceptionEvents, true);

    } catch (Exception e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Exception while save following events of a repetitive series",e);
      }
    }

  }

  @Override
  public void removeOneOccurrenceEvent(CalendarEvent originEvent,
                                       CalendarEvent removedOccurence,
                                       String username) {
    try {

      if(isEventSeriesGettingToLastItem(originEvent, username)) {
        removeRecurrenceSeries(username, originEvent);
        return;
      }

      String calendarId = originEvent.getCalendarId();
      boolean isException = false;
      if(originEvent.getExceptionIds() != null &&
          originEvent.getExceptionIds().contains(removedOccurence.getRecurrenceId())){
        isException = true;
      } else {
        originEvent.addExceptionId(removedOccurence.getRecurrenceId());
      }
      switch (Integer.parseInt(originEvent.getCalType())) {
      case Calendar.TYPE_PRIVATE:
        if(isException) {
          removeUserEvent(username, calendarId, removedOccurence.getId());
        }
        saveUserEvent(username, calendarId, originEvent, false);
        break;

      case Calendar.TYPE_PUBLIC:
        if(isException) {
          removePublicEvent(calendarId, removedOccurence.getId());
        } else
          savePublicEvent(calendarId, originEvent, false);
        for(CalendarEventListener cel : eventListeners_) {
          cel.removeOneOccurrence(originEvent, removedOccurence );
        }
        break;

      case Calendar.TYPE_SHARED:
        if(isException) {
          removeSharedEvent(username, calendarId, removedOccurence.getId());
        }
        saveEventToSharedCalendar(username, calendarId, originEvent, false);
        break;

      default:
        break;
      }
    } catch(Exception e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("exception occurs when removing one occurrence",e);
      }
    }
  }

  @Override
  public void removeAllSeriesEvents(CalendarEvent originEvent,
                                    String username) {
    try {
      List<CalendarEvent> events = getExceptionEvents(username, originEvent);
      events.add(originEvent);
      removeEvents(username, events, true);
    } catch (Exception e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("exception when removing all event in series", e);
      }
    }

  }

  @Override
  public void removeFollowingSeriesEvents(CalendarEvent originEvent, CalendarEvent selectedOccurrence,
                                          String username) {
    try {
      String timezone = getCalendarSetting(username).getTimeZone();
      TimeZone tz = TimeZone.getTimeZone(timezone);
      Date stopDate = Utils.getPreviousOccurrenceDate(originEvent, selectedOccurrence.getFromDateTime(),tz);
      String calendarId = originEvent.getCalendarId();

      Boolean isFirstOccurrence = (stopDate == null);
      if(isFirstOccurrence) {
        //if the selected occurrence is the first occurrence, remove all the series
        removeRecurrenceSeries(username, originEvent);
      } else {
        //otherwise, update the origin event and remove all the following
        originEvent.setRepeatUntilDate(stopDate);
        switch (Integer.parseInt(originEvent.getCalType())) {
        case Calendar.TYPE_PRIVATE:
          saveUserEvent(username, calendarId, originEvent, false);
          break;

        case Calendar.TYPE_PUBLIC:
          //we don't want to add old-content comment for origin event's activity
          storage_.savePublicEvent(calendarId, originEvent, false);
          for(CalendarEventListener listener : eventListeners_) {
            //add new comment to the origin event's activity (with new content format)
            listener.updateFollowingOccurrences(originEvent, stopDate);
          }
          break;

        case Calendar.TYPE_SHARED:
          saveEventToSharedCalendar(username, calendarId, selectedOccurrence, false);
          break;
        default:
          break;
        }

        List<CalendarEvent> exceptionEvents = getExceptionEventsFromDate(username, originEvent, selectedOccurrence.getFromDateTime());
        removeEvents(username, exceptionEvents, false);
      }

    } catch(Exception e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Exception when removing following events of a repetitive event",e);
      }
    }

  }

  @Override
  public List<CalendarEvent> getExceptionEventsFromDate(String username, CalendarEvent event, Date fromDate) throws Exception {
    List<CalendarEvent> exceptions = getExceptionEvents(username, event);
    List<CalendarEvent> result = new ArrayList<CalendarEvent>();
    if(fromDate != null) {
      for(CalendarEvent exceptionEvent : exceptions) {
        if(exceptionEvent.getFromDateTime().after(fromDate)) {
          result.add(exceptionEvent);
        }
      }
      return result;
    }
    return exceptions;
  }

  /* Utils for repetitive event */

  /*
   * updates the from time & to time of the origin event, no change to the origin date
   */
  private void updateOriginFromToTime(CalendarEvent originEvent, CalendarEvent occurrence) {
    // update original event from occurrence
    java.util.Calendar fromDate = Utils.getInstanceTempCalendar();
    fromDate.setTime(originEvent.getFromDateTime());
    java.util.Calendar newFromDate = Utils.getInstanceTempCalendar();
    newFromDate.setTime(occurrence.getFromDateTime());
    fromDate.set(java.util.Calendar.HOUR_OF_DAY, newFromDate.get(java.util.Calendar.HOUR_OF_DAY));
    fromDate.set(java.util.Calendar.MINUTE, newFromDate.get(java.util.Calendar.MINUTE));
    originEvent.setFromDateTime(fromDate.getTime());

    // calculate time amount
    java.util.Calendar newToDate = Utils.getInstanceTempCalendar();
    newToDate.setTime(occurrence.getToDateTime());
    int diffMinutes = (int) (newToDate.getTimeInMillis() - newFromDate.getTimeInMillis())
        / (60 * 1000);

    newToDate.setTime(fromDate.getTime());
    newToDate.add(java.util.Calendar.MINUTE, diffMinutes);
    originEvent.setToDateTime(newToDate.getTime());
  }

  private void fillOriginFromOccurrence(CalendarEvent originEvent, CalendarEvent occurrence) {
    originEvent.setSummary(occurrence.getSummary());
    originEvent.setDescription(occurrence.getDescription());
    originEvent.setEventCategoryId(occurrence.getEventCategoryId());
    originEvent.setEventCategoryName(occurrence.getEventCategoryName());
    originEvent.setMessage(occurrence.getMessage());
    originEvent.setLocation(occurrence.getLocation());
    List<Attachment> attachments = occurrence.getAttachment();
    originEvent.setAttachment(attachments);
    originEvent.setInvitation(occurrence.getInvitation());
    originEvent.setParticipant(occurrence.getParticipant());
    originEvent.setParticipantStatus(occurrence.getParticipantStatus());
    originEvent.setReminders(occurrence.getReminders());
    originEvent.setSendOption(occurrence.getSendOption());
    originEvent.setStatus(occurrence.getStatus());
    originEvent.setPriority(occurrence.getPriority());
    originEvent.setRepeatType(occurrence.getRepeatType());
    originEvent.setRepeatUntilDate(occurrence.getRepeatUntilDate());
    originEvent.setRepeatCount(occurrence.getRepeatCount());
    originEvent.setRepeatInterval(occurrence.getRepeatInterval());
    originEvent.setRepeatByDay(occurrence.getRepeatByDay());
    originEvent.setRepeatByMonthDay(occurrence.getRepeatByMonthDay());
    //originEvent.setExceptionIds(null);
  }


  private void removeEvents(String username, List<CalendarEvent> events, boolean isBroadcast)  {
    try {
      for(CalendarEvent event : events) {
        switch(Integer.parseInt(event.getCalType())) {
        case Calendar.TYPE_PRIVATE :
          removeUserEvent(username,event.getCalendarId(),event.getId());
          break;
        case Calendar.TYPE_PUBLIC :
          if(isBroadcast) removePublicEvent(event.getCalendarId(), event.getId());
          else {
            for (CalendarEventListener cel : eventListeners_) {
              cel.deletePublicEvent(event, event.getCalendarId());
            }
            storage_.removePublicEvent(event.getCalendarId(), event.getId());
          }
          break;
        case Calendar.TYPE_SHARED :
          removeSharedEvent(username, event.getCalendarId(), event.getId());
        }
      }
    } catch (Exception e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Exception when removing events",e);
      }
    }
  }

  public Attachment getAttachmentById(String attId){
    Attachment att = null;
    try {
      Node calendarApp = Utils.getPublicServiceHome(Utils.createSystemProvider());
      att = Utils.loadAttachment((Node)calendarApp.getSession().getItem(attId));
    } catch (Exception e) {
      if(LOG.isDebugEnabled()) LOG.debug(e.getMessage());
    }
    return att;
  }
  
  public void removeAttachmentById(String attId) {
    storage_.removeAttachmentById(attId);
  }

  public void addDataStore(ComponentPlugin dao) {
    if (dao instanceof Storage) {
      Storage storage = (Storage) dao;
      synchronized (this) {
          storages.put(storage.getId(), storage);
      }
    }
  }

  public JCRDataStorage getDataStorage() {
    return storage_;
  }

  @Override
  public CalendarHandler getCalendarHandler() {
    return calendarHandler;
  }

  @Override
  public EventHandler getEventHandler() {
    return eventHandler;
  }

  List<Storage> getAllStorage() {
    return new ArrayList<>(storages.values());
  }

  public Storage lookForDS(String id) {
    if (id != null) {
      return storages.get(id);
    }
    return storages.get(JCRStorage.JCR_STORAGE);
  }
}

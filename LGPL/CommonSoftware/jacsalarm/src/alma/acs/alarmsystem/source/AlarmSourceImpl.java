/*
 *    ALMA - Atacama Large Millimiter Array
 *    (c) European Southern Observatory, 2011
 *    Copyright by ESO (in the framework of the ALMA collaboration),
 *    All rights reserved
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation; either
 *    version 2.1 of the License, or (at your option) any later version.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with this library; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 *    MA 02111-1307  USA
 */
package alma.acs.alarmsystem.source;

import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import alma.acs.alarmsystem.source.AlarmQueue.AlarmToQueue;
import alma.acs.concurrent.ThreadLoopRunner;
import alma.acs.concurrent.ThreadLoopRunner.CancelableRunnable;
import alma.acs.concurrent.ThreadLoopRunner.ScheduleDelayMode;
import alma.acs.container.ContainerServicesBase;

/**
 * The implementation of {@link AlarmSource}.
 * <P>
 * Flushing of queued alarms is done by a thread to avoid blocking 
 * the caller if there are too many queued alarm.
 * There is possible critical race if the user executes a sequence
 * of queueAlarms()/flushAlarms(), it happens if the queueAlarms()
 * is called when the the thread has not yet finished flushing
 * the alarms.
 * This is avoided by copying the alarms in {@link AlarmSourceImpl#queue}
 * in a temporary immutable vector. 
 * 
 * @author acaproni
 *
 */
public class AlarmSourceImpl implements AlarmSource {
	
	/**
	 * The TimerTask to flush the queue of alarms 
	 * 
	 * @author acaproni
	 *
	 */
	private class QueueFlusherTask implements Runnable {
		// The local copy of the alarms to flush
		private final AlarmToQueue[] alarmsToFlush;
		
		/**
		 * Constructor
		 * 
		 * @param alarmsToFlush The alarms to flush
		 */
		public QueueFlusherTask(AlarmToQueue[] alarmsToFlush) {
			this.alarmsToFlush=alarmsToFlush;
		}
		
		@Override
		public void run() {
			for (AlarmToQueue alarm: alarmsToFlush) {
				setAlarm(alarm.faultFamily, alarm.faultMember, alarm.faultCode, alarm.getProperties(), alarm.active);
			}
		}
	}
	
	/**
	 * The thread activated after a delay to flush the alarms queued.
	 * <P>
	 * This class has been written to avoid using the unsafe {@link Timer}.
	 * {@link ThreadLoopRunner} is not either suitable because it runs a loop
	 * while we need a single run of this thread. 
	 * 
	 * @author acaproni
	 *
	 */
	private class TimerFlusherTask implements Runnable {
		/**
		 * The time (msecs) to wait before flushing the queue
		 */
		private final long delay;
		
		/**
		 * Signal if the task has been cancelled
		 */
		private volatile boolean cancelled=false;
		
		/**
		 * The thread executing this task
		 */
		private Thread thread=null;
		
		/**
		 * Constructor
		 * 
		 * @param delay The number of milliseconds to wait before
		 *              flushing the queue
		 */
		public TimerFlusherTask(long delay) {
			if (delay<=0) {
				throw new IllegalArgumentException("Invalid delay: "+delay);
			}
			this.delay=delay;
		}
		
		/**
		 * Setter
		 * 
		 * @param t The thread executing this task
		 */
		public void setThread(Thread t) {
			thread=t;
		}
		
		/**
		 * Cancel the current task
		 */
		public void cancel() {
			cancelled=true;
			if (thread!=null && !thread.isInterrupted()) {
				thread.interrupt();
			}
		}

		@Override
		public void run() {
			try {
				Thread.sleep(delay);
			} catch (InterruptedException ie) {
				// Propagate to the caller
				Thread.currentThread().interrupt();
			}
			if (!cancelled) {
				flushAlarms();
			}
		}
	}
	
	/**
	 * The loop to clear alarms every second.
	 * <P>
	 * The alarms to clear are inserted in {@link AlarmSourceImpl#alarmsToClean} and must be
	 * cleared if they are in the queue for more then {@link AlarmSourceImpl#ALARM_OSCILLATION_TIME}
	 * seconds.
	 * 
	 * @author acaproni
	 *
	 */
	private class OscillationTask extends CancelableRunnable {

		@Override
		public void run() {
			if (shouldTerminate || alarmsToClean.isEmpty()) {
				return;
			}
			Set<String> keys= alarmsToClean.keySet();
			for (String key: keys) {
				Long timestamp=alarmsToClean.get(key);
				if (timestamp==null) {
					// The entry has been removed by another task
					continue;
				}
				if (System.currentTimeMillis()-TimeUnit.SECONDS.toMillis(ALARM_OSCILLATION_TIME)>timestamp) {
					internalAlarmClear(key);
				}
			}
		}
		
	}
	
	/**
	 * To limit the effect of oscillations, publishing of inactive alarms 
	 * is delayed of <code>ALARM_OSCILLATION_TIME</code> (in seconds)
	 * <P>
	 * If during this time interval, the alarm is activated again then 
	 * it will not be deactivated.
	 * <P>
	 * Visibility is public for testing purposes.
	 */
	public static final int ALARM_OSCILLATION_TIME=1;
	
	/**
	 * The object to publish alarms
	 */
	private final AlarmSender alarmSender;
	
	/**
	 * The container services
	 */
	private final ContainerServicesBase containerServices;
	
	/**
	 * The alarms sent or terminated
	 */
	private final AlarmsMap alarms;

	/**
	 * The queue of alarms to be sent when the queuing will be disabled
	 */
	private final AlarmQueue queue = new AlarmQueue();
	
	/**
	 * Enable/disable the sending of alarms.
	 * <P>
	 * When the sending is disabled, new alarms are discarded.
	 */
	private volatile boolean enabled=true;
	
	/**
	 * When <code>true</code> the alarm are queued instead of being sent 
	 * immediately.
	 */
	private volatile boolean queuing=false;
	
	/**
	 * The loop to limit the oscillation
	 */
	private final ThreadLoopRunner oscillationLoop;
	
	/**
	 * The flusher for the queueing
	 * 
	 * Guarded by "this"
	 */
	private volatile TimerFlusherTask queueingTimerFlusherTask=null;
	
	/**
	 * The alarms to clean are initially stored in alarmToClean.
	 * <P> In fact the deletion of alarms is delayed of {@link AlarmSourceImpl#ALARM_OSCILLATION_TIME}
	 * to limit the effect of oscillation.
	 * <P> 
	 * The key is the ID of the alarm; the value is the time when the alarm
	 * has been sent.
	 */
	private final ConcurrentHashMap<String, Long>alarmsToClean=new ConcurrentHashMap<String, Long>();
	
	/**
	 * Constructor 
	 * 
	 * @param containerServices The container services
	 */
	public AlarmSourceImpl(ContainerServicesBase containerServices) {
		if (containerServices==null) {
			throw new IllegalArgumentException("Invalid null ContainerServicesBase");
		}
		this.containerServices=containerServices;
		alarms=new AlarmsMap(containerServices.getThreadFactory(),containerServices.getLogger());
		alarmSender=new AlarmSender(containerServices);
		oscillationLoop=new ThreadLoopRunner(
				new OscillationTask(), 
				ALARM_OSCILLATION_TIME, 
				TimeUnit.SECONDS, 
				containerServices.getThreadFactory(), 
				containerServices.getLogger());
		
	}

	@Override
	public void raiseAlarm(String faultFamily, String faultMember, int faultCode) {
		raiseAlarm(faultFamily,faultMember,faultCode,null);
	}

	@Override
	public void raiseAlarm(String faultFamily, String faultMember,
			int faultCode, Properties properties) {
		if (!enabled) {
			return;
		}
		if (queuing) {
			queue.add(faultFamily, faultMember, faultCode, properties, true);
			return;
		}
		String id= buildAlarmID(faultFamily, faultMember, faultCode);
		alarmsToClean.remove(id);
		if (!alarms.raise(id)) {
			alarmSender.sendAlarm(faultFamily, faultMember, faultCode, properties, true);
		}
	}

	/**
	 * The alarm to clear is not sent directly to the alarm service.
	 * It is instead queued in {@link AlarmSourceImpl#alarmsToClean} and 
	 * will be cleared by the oscillation loop.
	 */
	@Override
	public void clearAlarm(String faultFamily, String faultMember, int faultCode) {
		if (!enabled) {
			return;
		}
		if (queuing) {
			queue.add(faultFamily, faultMember, faultCode, null, false);
			return;
		}
		String id= buildAlarmID(faultFamily, faultMember, faultCode);
		alarmsToClean.putIfAbsent(id, System.currentTimeMillis());
	}
	
	/**
	 * Clear an alarm by sending to the alarm service.
	 *  
	 * @param id The id of the alarm
	 */
	private void internalAlarmClear(String id) {
		if (id==null || id.isEmpty()) {
			containerServices.getLogger().warning("Invalid alarm ID received "+id);
		}
		String[] alarmMembers=id.split(":");
		if (alarmMembers.length!=3) {
			containerServices.getLogger().warning("Invalid alarm ID received "+id);
		}
		String faultFamily=alarmMembers[0];
		String faultMember=alarmMembers[1];
		String faultCode=alarmMembers[2];
		if (!alarms.clear(id)) {
			alarmSender.sendAlarm(faultFamily, faultMember, Integer.parseInt(faultCode), false);
		}
	}

	@Override
	public void setAlarm(String faultFamily, String faultMember, int faultCode,
			Properties alarmProps, boolean active) {
		if (active) {
			raiseAlarm(faultFamily,faultMember,faultCode,alarmProps);
		} else {
			clearAlarm(faultFamily,faultMember,faultCode);
		}
	}
	
	@Override
	public void setAlarm(String faultFamily, String faultMember, int faultCode,
			boolean active) {
		setAlarm(faultFamily, faultMember,faultCode,null,active);
	}

	@Override
	public void terminateAllAlarms() {
		Collection<String> alarmsToTerminate = alarms.getActiveAlarms();
		for (String ID: alarmsToTerminate) {
			String[] strs=ID.split(":");
			clearAlarm(strs[0], strs[1], Integer.parseInt(strs[2]));
		}
	}

	@Override
	public synchronized void queueAlarms(long delayTime) {
		queuing=true;
		if (queueingTimerFlusherTask!=null) {
			queueingTimerFlusherTask.cancel();
		}
		queueingTimerFlusherTask=new TimerFlusherTask(delayTime);
		Thread thread=containerServices.getThreadFactory().newThread(queueingTimerFlusherTask);
		queueingTimerFlusherTask.setThread(thread);
		thread.setName("TimerFlusherTask");
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public synchronized void queueAlarms(long delayTime, TimeUnit unit) {
		queueAlarms(unit.toMillis(delayTime));
	}

	@Override
	public synchronized void queueAlarms() {
		queuing=true;
	}

	@Override
	public synchronized void flushAlarms() {
		if (queueingTimerFlusherTask!=null) {
			queueingTimerFlusherTask.cancel();
		}
		AlarmToQueue[] temp = new AlarmToQueue[queue.size()];
		queue.toArray(temp);
		Thread thread=containerServices.getThreadFactory().newThread(new QueueFlusherTask(temp));
		thread.setName("AlarmQueueFlusher");
		thread.setDaemon(true);
		thread.start();
		queuing=false;
	}

	@Override
	public void disableAlarms() {
		enabled=false;
	}

	@Override
	public void enableAlarms() {
		enabled=true;
	}
	
	/**
	 * Build the alarm ID from the triplet.
	 * <P>
	 * This method ensures that all the triplet are built in the right and consistent
	 * way between the different calls. This is particularly important given
	 * that the ID is the key used in the map.
	 * 
	 * @param faultFamily The fault family
	 * @param faultMember The fault member
	 * @param faultCode The fault code
	 * @return The ID of the alarm
	 */
	private String buildAlarmID(String faultFamily, String faultMember, int faultCode) {
		return faultFamily+":"+faultMember+":"+faultCode;
	}
	
	/**
	 * Start the threads
	 */
	public void start() {
		oscillationLoop.setDelayMode(ScheduleDelayMode.FIXED_DELAY);
		oscillationLoop.runLoop();
		alarms.start();
	}
	
	@Override
	public void tearDown() {
		enabled=false;
		if (queueingTimerFlusherTask!=null) {
			queueingTimerFlusherTask.cancel();
		}
		alarmsToClean.clear();
		try {
			oscillationLoop.shutdown(2, TimeUnit.SECONDS);
		} catch (InterruptedException ie) {
			System.err.println("Error shutting down the oscillation timer task");
		}
		alarmSender.close();
		alarms.shutdown();
		queue.clear();
	}
}

/*******************************************************************************
* ALMA - Atacama Large Millimiter Array
* (c) UNSPECIFIED - FILL IN, 2005
*
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU Lesser General Public
* License as published by the Free Software Foundation; either
* version 2.1 of the License, or (at your option) any later version.
*
* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this library; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
*
* "@(#) $Id: loggingACSRemoteAppender.cpp,v 1.1 2011/02/14 21:15:08 javarias Exp $"
*/

#include "loggingACSRemoteAppender.h"
#include <log4cpp/Priority.hh>

#include <SystemException.h>


using namespace logging;

AcsLogLevels::logLevelValue toIdlLogLevel(log4cpp::Priority::Value prio) {
	switch (prio) {
	case log4cpp::Priority::TRACE:
		return AcsLogLevels::TRACE_VAL;
	case log4cpp::Priority::DELOUSE:
		return AcsLogLevels::DELOUSE_VAL;
	case log4cpp::Priority::DEBUG:
		return AcsLogLevels::DEBUG_VAL;
	case log4cpp::Priority::INFO:
		return AcsLogLevels::INFO_VAL;
	case log4cpp::Priority::NOTICE:
		return AcsLogLevels::NOTICE_VAL;
	case log4cpp::Priority::WARNING:
		return AcsLogLevels::WARNING_VAL;
	case log4cpp::Priority::ERROR:
		return AcsLogLevels::ERROR_VAL;
	case log4cpp::Priority::CRITICAL:
		return AcsLogLevels::CRITICAL_VAL;
	case log4cpp::Priority::ALERT:
		return AcsLogLevels::ALERT_VAL;
	case log4cpp::Priority::EMERGENCY:
		return AcsLogLevels::EMERGENCY_VAL;
	default:
		return (AcsLogLevels::logLevelValue)-1;
	}
}

ACSRemoteAppender::ACSRemoteAppender(const std::string& name,
		unsigned long cacheSize,
		unsigned int autoFlushTimeoutSec,
		Logging::AcsLogService_ptr centralizedLogger,
		int maxLogsPerSecond = -1) :
	log4cpp::LayoutAppender(name),
	_cacheSize(cacheSize),
	_flushTimeout(autoFlushTimeoutSec),
	_logger(Logging::AcsLogService::_duplicate(centralizedLogger)),
	_threshold(log4cpp::Priority::NOTSET),
	_filter(NULL),
	_workCond(_workCondThreadMutex){

	_logThrottle = new LogThrottle(maxLogsPerSecond);
	_cache = new std::deque<Logging::XmlLogRecord>();
	if(_cacheSize > 0) {
		// set the max size of the deque just in case appender could have a log overflow
		_cache->resize(cacheSize * 2);
		ACE_Thread::spawn(static_cast<ACE_THR_FUNC>(ACSRemoteAppender::worker), this);
	}
}

ACSRemoteAppender::~ACSRemoteAppender() {
	close();
	delete _logThrottle;
	delete _cache;
}


void ACSRemoteAppender::_append(const log4cpp::LoggingEvent& event) {
	std::string message = _getLayout().format(event);
	if (_cacheSize > 0) {
		if (_logThrottle->checkPublishLogRecord() > 0) {
			_logThrottle->updateLogCounter(1);
			Logging::XmlLogRecord log;
			log.logLevel = toIdlLogLevel(event.priority);
			log.xml = message.c_str();
			//if the log is quite urgent put it directly in the remote log service
			if (event.priority == log4cpp::Priority::EMERGENCY)
				sendLog(log);
			else
				_cache->push_back(log);
		}
		if (_cache->size() >= _cacheSize) {
			_workCond.signal();
		}
	}
	else {
		if (_logThrottle->checkPublishLogRecord() > 0) {
			_logThrottle->updateLogCounter(1);
			Logging::XmlLogRecord log;
			log.logLevel = toIdlLogLevel(event.priority);
			log.xml = message.c_str();
			sendLog(log);
		}
	}
}

void ACSRemoteAppender::close() {
	_stopThread = true;
	_workCond.signal();
	flushCache();
	//flush the cache and stop the flushing thread
}

void ACSRemoteAppender::sendLog(Logging::XmlLogRecord& log) {
	Logging::XmlLogRecordSeq seq;
	seq.length(1);
	seq[0] = log;
	sendLog(seq);
}

void ACSRemoteAppender::sendLog(Logging::XmlLogRecordSeq& logs) {
	if (CORBA::is_nil(_logger)) {
		std::cerr << "Remote logger is not available" << std::endl;
		return;
	}
	try {
		_logger->writeRecords(logs);
	} catch (CORBA::SystemException &ex) {
		std::cerr << "Problem writing to the centralized logger." << std::endl;
	}
}

void ACSRemoteAppender::flushCache() {
	Logging::XmlLogRecordSeq logs;
	logs.length(_cacheSize * 2);
	unsigned int count = 0;

	while (!_cache->empty() || count == (_cacheSize * 2)) {
		logs[count++] = _cache->front();
		_cache->pop_front();
	}
	logs.length(count);
	sendLog(logs);
}

void* ACSRemoteAppender::worker(void* arg) {
	static_cast<ACSRemoteAppender*>(arg)->svc();
	return 0;
}

void ACSRemoteAppender::svc() {
	while (!_stopThread) {
		ACE_Time_Value timeout = ACE_OS::gettimeofday() + ACE_Time_Value(
				_flushTimeout, 0);
		_workCondThreadMutex.acquire();
		_workCond.wait(&timeout);
		_workCondThreadMutex.release();
		//if the thread was shut down, the close method should flush the cache
		if (!_stopThread)
			flushCache();
	}
}

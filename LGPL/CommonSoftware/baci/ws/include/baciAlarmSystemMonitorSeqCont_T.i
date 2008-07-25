
/*********************************** IMPLEMENTATION of AlarmSystemMonitorSeqCont */
template<class T, class TPROP>
baci::AlarmSystemMonitorSeqCont<T, TPROP>::AlarmSystemMonitorSeqCont(TPROP * property, EventDispatcher * eventDispatcher) :
    baci::AlarmSystemMonitor<TPROP>(property, eventDispatcher)
{
    ACS_TRACE("baci::AlarmSystemMonitorSeqCont&lt;&gt;::AlarmSystemMonitorSeqCont");
    alarmsRaised_mp = 0;
}//AlarmSystemMonitorSeqCont

template<class T, class TPROP>
baci::AlarmSystemMonitorSeqCont<T, TPROP>::~AlarmSystemMonitorSeqCont()
{
    ACS_TRACE("baci::AlarmSystemMonitorSeqCont&lt;&gt;::~AlarmSystemMonitorSeqCont");
    if (alarmsRaised_mp !=0)
    { 
    	delete[] alarmsRaised_mp; 
    	alarmsRaised_mp = 0; 
    	alarmsRaisedLength_m = 0;
    }//if
}//~AlarmSystemMonitorSeqCont


template<class T, class TPROP>
void baci::AlarmSystemMonitorSeqCont<T, TPROP>::check(BACIValue &val,
		const ACSErr::Completion & c,
		const ACS::CBDescOut & desc)
{
	ACE_UNUSED_ARG(c);
	T valueSeq = val.getValue(static_cast<T*>(0));

	if (alarmsRaisedLength_m!=valueSeq.length())
	{
		if (alarmsRaised_mp !=0)
		{ 
			delete[] alarmsRaised_mp; 
			alarmsRaised_mp = 0; 
			alarmsRaisedLength_m = 0;
		}

		alarmsRaisedLength_m = valueSeq.length();
		alarmsRaised_mp = new int[alarmsRaisedLength_m];

		// initialize to no alarm
		for (unsigned int i = 0; i < alarmsRaisedLength_m; i++) 
			alarmsRaised_mp[i] = 0; //=no alarm
	}//if


	for (CORBA::ULong n = 0UL; n < valueSeq.length(); n++)
	{	
		if ((alarmsRaised_mp[n]!=0) &&			// we have an alarm (0 indicates no alarm)
				(valueSeq[n]>=this->property_mp->alarm_low_off()) && 
				(valueSeq[n]<=this->property_mp->alarm_high_off()))
		{
			std::ostringstream ostr;
			std::string ts;
			ostr << valueSeq[n] << std::ends;	
			ts =  ostr.str(); // we have to make a temporary string otherwise there is problem with memory:  s = ostr.str().c_str(); does not work
			ACS_SHORT_LOG((LM_ALERT, "Alarm for property: %s[%d] cleared. Value change to: %s", this->property_mp->name(), n, ts.c_str()));
			ostr.str("");
			ostr << this->property_mp->name() << "[" << n << "]";
			this->sendAlarm("BACIProperty", ostr.str(), 1, false);
			alarmsRaised_mp[n] = 0;//=cleared
		}
		else if ((alarmsRaised_mp[n]!=-1) &&            // if not alarm low
				(valueSeq[n]<=this->property_mp->alarm_low_on()))
		{
			std::ostringstream ostr;
			std::string ts;
			ostr << valueSeq[n] << std::ends;	
			ts =  ostr.str(); // we have to make a temporary string otherwise there is problem with memory:  s = ostr.str().c_str(); does not work
			ACS_SHORT_LOG((LM_ALERT, "Alarm for property: %s[%d] raised. Value change to: %s", this->property_mp->name(), n, ts.c_str()));
			ostr.str("");
			ostr << this->property_mp->name() << "[" << n << "]";
			this->sendAlarm("BACIProperty", ostr.str(), 1, true);
			alarmsRaised_mp[n] = -1;//raised value too low
		}
		else if ((alarmsRaised_mp[n]!=1) &&            // if not alarm hi 
				(valueSeq[n]>=this->property_mp->alarm_high_on()))
		{
			std::ostringstream ostr;
			std::string ts;
			ostr << valueSeq[n] << std::ends;	
			ts =  ostr.str(); // we have to make a temporary string otherwise there is problem with memory:  s = ostr.str().c_str(); does not work
			ACS_SHORT_LOG((LM_ALERT, "Alarm for property: %s[%d] raised. Value change to: %s", this->property_mp->name(), n, ts.c_str()));
			ostr.str("");
			ostr << this->property_mp->name() << "[" << n << "]";
			this->sendAlarm("BACIProperty", ostr.str(), 1, true);
			alarmsRaised_mp[n] = 1;//raised value too high
		}
	}// for loop
}//check

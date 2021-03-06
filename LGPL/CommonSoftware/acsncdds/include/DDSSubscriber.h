#ifndef _DDS_SUBSCRIBER_H
#define _DDS_SUBSCRIBER_H

#include <DDSHelper.h>
#include <acsddsncDataReaderListener.h>
#include <dds/DCPS/SubscriberImpl.h>

namespace ddsnc{

	/**
	 * The Subscriber implementation of ACS Notification Channel based on DDS
	 *
	 * @see DDSHelper
	 * @author Jorge Avarias <javarias[at]inf.utfsm.cl>
	 */
	class DDSSubscriber : public ddsnc::DDSHelper{
		private:
		DDS::Subscriber_var sub;
		OpenDDS::DCPS::SubscriberImpl *sub_impl;
		DDS::DataReaderListener_var *listener;
		
		int attachToTransport();

		/**
		 * Creates the subscriber with default QoS or QoS with a partition
		 *
		 * @see initialize();
		 */
		int createSubscriber();

		protected:
		DDS::SubscriberQos subQos;

		public:
		DDS::DataReaderQos drQos; /**<  Data Writer Qos, can be modified according
											 OpenDDS API, the Qos properties will be 
											 applied when is called consumerReady for
											 first time and they cannot be changed after
											 that.

											 @see consumerReady()
											 @see initialize()
											 */

		/**
		 * Constructor of the class.
		 *
		 * @see DDSHelper
		 */
		DDSSubscriber(CORBA::String_var channel_name);
		
		~DDSSubscriber()
		{
			delete listener;
		}

		/**
		 * Initialize the DataReader with Qos. This method leaves the Subscriber
		 * initializated when is executed.
		 *
		 * @see drQos
		 */
		void consumerReady();

		/**
		 * The method maintains the name provided by the old acsnc API.
		 * Actually this function initializes the DataReader listener.
		 *
		 * The <type> corresponds to the name of data type (without namespace)
		 * defined in the idl file, the classes required by the template are 
		 * autogenerated by dcps_ts.pl tools and are specific to the data 
		 * type defined.
		 *
		 * @tparam D <type> struct
		 * @tparam DR <type>DataReader class
		 * @tparam DRV <type>DataReader_var class
		 * @param (*templateFunction)(D, void *) callback function to handle
		 * data received by DataReaderListener
		 * @param *handlerParam 
		 */
		template <class DRV, class DR, class D>
			void addSubscription(
					void (*templateFunction)(D, void *), void *handlerParam=0)
			{
				std::cerr << "DDSSubscriber::addSubscription" << std::endl;

				listener = new  DDS::DataReaderListener_var 
					(new ddsnc::ACSDDSNCDataReaderListener
					 <DRV,DR,D>(templateFunction, handlerParam));

				/*ddsnc::ACSDDSNCDataReaderListener<DRV,DR,D>* listener_servant=
					dynamic_cast<ddsnc::ACSDDSNCDataReaderListener<DRV,DR,D>*>
					(listener.in());

				if(CORBA::is_nil (listener.in())){
					std::cerr << "listener is nil" << std::endl;
				}
				*/
			}


		/**
		 * Create the participant, initialize the subscriber with the partition
		 * provided in the channelName constructor parameter, if there is not
		 * partition, initialize the subscriber with default QoS properties, then
		 * initialize the transport for the participant, create the subscriber
		 * and initialize the topic with topicNanme provided as parameter of
		 * the constructor and with data type support defined by templates and
		 * finally initialize the data reader QoS with default values
		 *
		 * The <type> corresponds to the name of data type (without namespace)
		 * defined in the idl file, the classes required by the template are 
		 * autogenerated by dcps_ts.pl tools and are specific to the data 
		 * type defined.
		 *
		 *
		 * @tparam D <type> struct
		 * @tparam TSV <type>TypeSupport_var class
		 * @tparam TSI <type>TypeSupportImpl class
		 */
		template <class D, class TSV, class TSI>
		void initialize()
		{
			std::cerr<< "DDSSubscriber::initialize()" << std::endl;
			createParticipant();
			if (CORBA::is_nil (participant.in()))
				 std::cerr << "Participant is nil" << std::endl;
		
			if(partitionName!=NULL){
				participant->get_default_subscriber_qos(subQos);
				subQos.partition.name.length(1);
				subQos.partition.name[0]=CORBA::string_dup(partitionName);
			}
			initializeTransport();
		
			createSubscriber();
			
			/*Initialize Type Support*/
			TSV ts;
			ts = new TSI();
			if (DDS::RETCODE_OK != ts->register_type(participant.in(),"")){
				std::cerr << "register_type failed" << std::endl;
			}
			
			/*Initialize the Topic*/
			initializeTopic(ts->get_type_name());
			if(CORBA::is_nil(topic.in()))
				std::cerr<< "Topic is nil" << std::endl;
		
			sub->get_default_datareader_qos (drQos);
			
			drQos.reliability.kind = ::DDS::RELIABLE_RELIABILITY_QOS;
			drQos.reliability.max_blocking_time.sec = 1;

			drQos.history.kind = ::DDS::KEEP_LAST_HISTORY_QOS;
			// drQos.history.depth = 100;
			drQos.history.depth = 1;
		}
	};
}

/**
 * Create a new DDS Subscriber in an easy way.
 *
 * Create a new DDSSubscribe object, then initialize the subscribe and finally
 * execute the addSubscription function.
 *
 * @see DDSSubscriber()
 * @see initialize()
 * @see addSubscription()
 */
#define ACS_NEW_DDS_SUBSCRIBER(subscriber_p, idlStruct, channelName, handlerFunc, handlerParam) \
{ \
	subscriber_p= new ddsnc::DDSSubscriber(channelName); \
	subscriber_p->initialize<idlStruct, idlStruct##TypeSupport_var, idlStruct##TypeSupportImpl>(); \
	subscriber_p->addSubscription<idlStruct##DataReader_var, idlStruct##DataReader, idlStruct>(handlerFunc, handlerParam); \
}

#endif

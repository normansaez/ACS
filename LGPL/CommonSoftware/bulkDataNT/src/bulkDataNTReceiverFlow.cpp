/*******************************************************************************
* ALMA - Atacama Large Millimiter Array
* (c) European Southern Observatory, 2011
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
* "@(#) $Id: bulkDataNTReceiverFlow.cpp,v 1.5 2011/07/29 12:00:39 bjeram Exp $"
*
* who       when      what
* --------  --------  ----------------------------------------------
* bjeram  2011-04-19  created
*/

#include "bulkDataNTReceiverFlow.h"
#include <iostream>

#include <AV/FlowSpec_Entry.h>  // we need it for TAO_Tokenizer ??


static char *rcsId="@(#) $Id: bulkDataNTReceiverFlow.cpp,v 1.5 2011/07/29 12:00:39 bjeram Exp $";
static void *use_rcsId = ((void)&use_rcsId,(void *) &rcsId);

using namespace AcsBulkdata;
using namespace std;

BulkDataNTReceiverFlow::BulkDataNTReceiverFlow(BulkDataNTStream *receiverStream, const char* flowName, BulkDataCallback *cb, bool releaseCB) :
		receiverStream_m(receiverStream),
		flowName_m(flowName), callback_m(cb), releaseCB_m(releaseCB)
{
	AUTO_TRACE(__PRETTY_FUNCTION__);
	std::string topicName;

	// should be refactor to have just one object for comunication !! DDSDataWriter or similar
	ddsSubscriber_m = new BulkDataNTDDSSubscriber(receiverStream_m->getDDSParticipant());

	topicName = receiverStream_m->getName() + "#" + flowName_m;
	ddsTopic_m = ddsSubscriber_m->createDDSTopic(topicName.c_str());

	dataReaderListener_m = new BulkDataNTReaderListener(topicName.c_str(), callback_m);

	ddsDataReader_m= ddsSubscriber_m->createDDSReader(ddsTopic_m, dataReaderListener_m);

	callback_m->setStreamName(receiverStream_m->getName().c_str());
	callback_m->setFlowName(topicName.c_str());
}//BulkDataNTReceiverFlow


BulkDataNTReceiverFlow::~BulkDataNTReceiverFlow()
{
	AUTO_TRACE(__PRETTY_FUNCTION__);
	receiverStream_m->removeFlowFromMap(flowName_m.c_str());
	// this part can go to BulkDataNTDDSPublisher, anyway we need to refactor
	DDS::DomainParticipant *participant = receiverStream_m->getDDSParticipant();
	if (participant!=0)
	{
		ddsSubscriber_m->destroyDDSReader(ddsDataReader_m);
		ddsDataReader_m = 0;
		delete dataReaderListener_m;
		participant->delete_topic(ddsTopic_m);
	}
	else
	{
		ACS_SHORT_LOG((LM_ERROR, "Problem deleting data reader and topic participant is NULL"));
	}
	delete ddsSubscriber_m;
	if (releaseCB_m) delete callback_m;
}//~BulkDataNTReceiverFlow


/*___oOo___*/
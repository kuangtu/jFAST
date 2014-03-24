package com.ociweb.jfast.stream;

import com.ociweb.jfast.error.FASTException;
import com.ociweb.jfast.field.TokenBuilder;
import com.ociweb.jfast.field.TypeMask;
import com.ociweb.jfast.loader.TemplateCatalog;
import com.ociweb.jfast.primitive.PrimitiveWriter;

public class FASTDynamicWriter {

	private final FASTWriterDispatch writerDispatch;
	private final TemplateCatalog catalog;
	private final int[] fullScript;
	private final FASTRingBuffer ringBuffer;
	
	private int activeScriptCursor;
	private int activeScriptLimit;
	boolean needTemplate = true;
	
	public FASTDynamicWriter(PrimitiveWriter primitiveWriter, TemplateCatalog catalog, FASTRingBuffer ringBuffer, FASTWriterDispatch writerDispatch) {

		this.writerDispatch = writerDispatch;

		this.catalog = catalog;
		this.fullScript = catalog.fullScript();
		this.ringBuffer = ringBuffer;
	}

	
	//non blocking write, returns if there is nothing to do.
	public void write() {
		//write from the the queue/ringBuffer 
		//queue will contain one full unit to be processed.
		//Unit: 1 Template/Message or 1 EntryInSequence
		
		//because writer does not move pointer up until full unit is ready to go
		//we only need to check if data is available, not the size.
		if (ringBuffer.hasContent()) {
			int idx = 0;		
			
			if (needTemplate) {
				//template processing (can these be nested?) 
				int templateId = ringBuffer.readInteger(idx); 	
				idx++;
				//tokens - reading 
				activeScriptCursor = catalog.getTemplateStartIdx(templateId);
				activeScriptLimit = catalog.getTemplateLimitIdx(templateId);
				
				if (0==activeScriptLimit && 0==activeScriptCursor) {
					throw new FASTException("Unknown template:"+templateId);
				}
				
				System.err.println("tmpl "+ringBuffer.remPos+"  templateId:"+templateId+" script:"+activeScriptCursor+"_"+activeScriptLimit);
				if (62==ringBuffer.remPos) {
					System.err.println("xxxxx ");
				}
			}
						
			do{
				int token = fullScript[activeScriptCursor];
								
				if (writerDispatch.dispatchWriteByToken(token, idx)) {
					
					//finished both end of message and end of sequence				
					if (1+activeScriptCursor==activeScriptLimit) {
						needTemplate = true;
						idx += stepSizeInRingBuffer(token);
						ringBuffer.removeForward(idx);
						activeScriptCursor++;
						//System.err.println("xx "+idx);
						return;
					}
					
					int seqScriptLength = TokenBuilder.MAX_INSTANCE&fullScript[1+activeScriptCursor];
					if (writerDispatch.isSkippedSequence()) {
						System.err.println("skipped foreward :"+seqScriptLength);
						//jump over sequence group in script
						activeScriptCursor += seqScriptLength;
					} else if (!writerDispatch.isFirstSequenceItem()) {						
				    	//jump back to top of this sequence in the script.
						System.err.println(TokenBuilder.tokenToString(fullScript[activeScriptCursor])+
								           " jump to "+TokenBuilder.tokenToString(fullScript[activeScriptCursor-seqScriptLength]));
						
						activeScriptCursor -= seqScriptLength;
						
						
						
					} else {
						activeScriptCursor++;
					}
					
					needTemplate = false;
					idx += stepSizeInRingBuffer(token);
					ringBuffer.removeForward(idx);
					//System.err.println("yy "+idx);
					return;
				}
				
				idx += stepSizeInRingBuffer(token);
				
			
			} while (++activeScriptCursor<activeScriptLimit);
			needTemplate = true;
			ringBuffer.removeForward(idx);
		}
		
	}

	public void reset(boolean clearData) {

		needTemplate=true;
		
		activeScriptCursor=0;
		activeScriptLimit=0;
		
    	if (clearData) {
    		this.writerDispatch.reset();
    	}
	}
	
	
	private int stepSizeInRingBuffer(int token) {
		int stepSize = 0;
		if (0==(token&(16<<TokenBuilder.SHIFT_TYPE))) {
			//0????
			if (0==(token&(8<<TokenBuilder.SHIFT_TYPE))) {
				//00???
				if (0==(token&(4<<TokenBuilder.SHIFT_TYPE))) {
					//int
					stepSize = 1;
				} else {
					//long
					stepSize = 2;
				}
			} else {
				//01???
				if (0==(token&(4<<TokenBuilder.SHIFT_TYPE))) {
					//int for text (takes up 2 slots)
					stepSize = 2;			
				} else {
					//011??
					if (0==(token&(2<<TokenBuilder.SHIFT_TYPE))) {
						//0110? Decimal and DecimalOptional
						stepSize = 3;
					} else {
						//int for bytes
						stepSize = 0;//BYTES ARE NOT IMPLEMENTED YET BUT WILL BE 2;
					}
				}
			}
		} else {
			if (0==(token&(8<<TokenBuilder.SHIFT_TYPE))) {
				//10???
				if (0==(token&(4<<TokenBuilder.SHIFT_TYPE))) {
					//100??
					//Group Type, no others defined so no need to keep checking
					stepSize = 0;
				} else {
					//101??
					//Length Type, no others defined so no need to keep checking
					//Only happens once before a node sequence so push it on the count stack
					stepSize = 1;
				}
			} else {
				//11???
				//Dictionary Type, no others defined so no need to keep checking
				stepSize = 0;
			}
		}
	//	System.err.println("inc by:"+stepSize+" token "+TokenBuilder.tokenToString(token));
		return stepSize;
	}

}
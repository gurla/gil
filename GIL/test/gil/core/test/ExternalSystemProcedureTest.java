/*
    Copyright (C) 2010 LearningWell AB (www.learningwell.com), Kärnkraftsäkerhet och Utbildning AB (www.ksu.se)

    This file is part of GIL (Generic Integration Layer).

    GIL is free software: you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    GIL is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with GIL.  If not, see <http://www.gnu.org/licenses/>.
*/
package gil.core.test;

import gil.common.Result;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import gil.core.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import gil.io.IExternalSystemAdapter;
import gil.common.GILConfiguration;
import gil.io.ESAdapterCapabilities;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * @author Göran Larsson @ LearningWell AB
 */
public class ExternalSystemProcedureTest {

    final static int BUF_SIZE = 8;
    IntegrationContext context = new IntegrationContext();

    static GILConfiguration config = mock(GILConfiguration.class);
    static {
        when(config.getESAdapterReadPollRate()).thenReturn(1);
        when(config.getPMAdapterByteOrder()).thenReturn(ByteOrder.BIG_ENDIAN);
    }
    
    IExternalSystemAdapter _esAdapterMock = mock(IExternalSystemAdapter.class);
    ExternalSystemProcedure _procedure = new ExternalSystemProcedure(_esAdapterMock, context, BUF_SIZE, config);

    ////////////////////////////////////////////////////////////////////////////////////////
    // Tests of successful and unsuccessful establishment of a connection to the external system
    ////////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void expect_established_connection_to_external_system_after_first_call_to_runOnce() throws Exception {

        context.pendingSimCommands.add(new Command(""));
        context.pendingTransferToES.add(ByteBuffer.allocate(100));

        when(_esAdapterMock.connect()).thenReturn(true);
        _procedure.runOnce(1);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.ConnectedState);
        assertEquals(0, context.pendingTransferToES.size());
        assertEquals(0, context.pendingSimCommands.size());
    }

    @Test
    public void expect_connect_to_be_called_repeadetly_when_connect_returns_false() throws Exception {
        when(_esAdapterMock.connect()).thenReturn(false);
        _procedure.runOnce(1);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
        _procedure.runOnce(2);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
        when(_esAdapterMock.connect()).thenReturn(true);
        _procedure.runOnce(3);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.ConnectedState);
    }

    @Test
    public void expect_es_state_and_status_to_change_depending_on_current_state_of_the_procedure() throws Exception {        
        when(_esAdapterMock.getCapabilities()).thenReturn(new ESAdapterCapabilities());
        when(_esAdapterMock.getStatus()).thenReturn(new SystemStatus(SystemStatus.OK, "its OK"));
        when(_esAdapterMock.getState()).thenReturn(SimState.SLOW);
        when(_esAdapterMock.connect()).thenReturn(true);
        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenReturn(null);

        // Expect unknown state and status when adapter is disconnected
        assertEquals(SimState.UNKNOWN, _procedure.getExternalSystemState());
        assertEquals(SystemStatus.UNKNOWN, _procedure.getExternalSystemStatus().getStatusCode());

        // When connected, expect status and state to be read from the adapter.
        _procedure.runOnce(1);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.ConnectedState);
        assertEquals(SimState.SLOW, _procedure.getExternalSystemState());
        assertEquals("its OK", _procedure.getExternalSystemStatus().getDescription());
        
        // Expect to be disconnected the status and state to be unknown after the adapter has thrown exception.
        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenThrow(new IOException("aaaaa"));
        _procedure.runOnce(2);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
        assertEquals(SimState.UNKNOWN, _procedure.getExternalSystemState());
        assertEquals(SystemStatus.UNKNOWN, _procedure.getExternalSystemStatus().getStatusCode());
        
        // Expect to be in error state when adapter has thrown exception when connecting. Expect state to be
        // unknown and status to be NOK. The status description is expected to be the message as the error message
        // in the exception thrown by the adapter.
        when(_esAdapterMock.connect()).thenThrow(new IOException("errmsg from ES-adapter"));
        _procedure.runOnce(3);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.ErrorState);
        assertEquals(SimState.UNKNOWN, _procedure.getExternalSystemState());
        assertEquals(SystemStatus.NOK, _procedure.getExternalSystemStatus().getStatusCode());
        assertEquals("errmsg from ES-adapter", _procedure.getExternalSystemStatus().getDescription());
    }

    @Test
    public void expect_error_state_if_connection_to_external_system_fails() throws Exception {
        when(_esAdapterMock.connect()).thenThrow(new IOException("errmsg from ES-adapter"));
        _procedure.runOnce(1);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.ErrorState);
    }

    @Test
    public void expect_state_transition_from_error_to_disconnected_when_reconnect_is_called() throws Exception {
        when(_esAdapterMock.connect()).thenThrow(new IOException("errmsg from ES-adapter"));
        _procedure.runOnce(1);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.ErrorState);
        _procedure.reconnect();
        _procedure.runOnce(1);
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
    }

    @Test
    public void expect_state_transition_from_connected_to_disconnected_when_reconnect_is_called() throws Exception {
        _doFirstRunOnceCallToConnect();
        _procedure.reconnect();
        _procedure.runOnce(1);
        verify(_esAdapterMock).disconnect();
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Tests of successful and unsuccessful execution of pending sim commands
    ////////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void expect_single_pending_command_to_be_executed() throws Exception {
        _doFirstRunOnceCallToConnect();
        context.pendingSimCommands.add(new Command("FREEZE"));

        when(_esAdapterMock.executeSimCommand((Command)any())).thenReturn(new Result(true));

        _procedure.runOnce(1);

        ArgumentCaptor<Command> argument = ArgumentCaptor.forClass(Command.class);
        verify(_esAdapterMock).executeSimCommand(argument.capture());
        assertEquals(0, context.pendingSimCommands.size());
        assertEquals("FREEZE", argument.getValue().getID());
    }

    @Test
    public void expect_multiple_pending_commands_to_be_executed_in_correct_sequence() throws Exception {

        ArgumentCaptor<Command> argument = ArgumentCaptor.forClass(Command.class);

        _doFirstRunOnceCallToConnect();
        context.pendingSimCommands.add(new Command("FREEZE"));
        context.pendingSimCommands.add(new Command("RUN"));

        when(_esAdapterMock.executeSimCommand((Command)any())).thenReturn(new Result(true));

        _procedure.runOnce(1);
        
        verify(_esAdapterMock).executeSimCommand(argument.capture());
        assertEquals(1, context.pendingSimCommands.size());
        assertEquals("FREEZE", argument.getValue().getID());

        _procedure.runOnce(2);

        verify(_esAdapterMock, times(2)).executeSimCommand(argument.capture());
        assertEquals(0, context.pendingSimCommands.size());
        assertEquals("RUN", argument.getValue().getID());
    }


    @Test
    public void expect_disconnect_from_ES_when_an_IOException_is_thrown_during_execution_of_a_command() throws Exception {

        _doFirstRunOnceCallToConnect();
        context.pendingSimCommands.add(new Command("FREEZE"));
        context.pendingSimCommands.add(new Command("RUN"));

        when(_esAdapterMock.executeSimCommand((Command)any())).thenThrow(new IOException());

        _procedure.runOnce(1);

        verify(_esAdapterMock).disconnect();
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
    }

    @Test
    public void expect_command_execution_failure_count_to_be_incremented_if_failure_when_executing_command() throws Exception {

        _doFirstRunOnceCallToConnect();
        context.pendingSimCommands.add(new Command("FREEZE"));
        context.pendingSimCommands.add(new Command("RUN"));

        when(_esAdapterMock.executeSimCommand((Command)any())).thenReturn(new Result(false, "a faliure"));

        _procedure.runOnce(1);

        assertEquals(1, _procedure.getCommandWriteFailureCount());
        assertEquals(1, context.pendingSimCommands.size());
    }


    ////////////////////////////////////////////////////////////////////////////////////////
    // Tests of successful and unsuccessful write of pending signal data to the external system
    ////////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void expect_single_pending_data_to_be_written() throws Exception {

        _doFirstRunOnceCallToConnect();
        final ByteBuffer buffer = ByteBuffer.allocate(BUF_SIZE);
        buffer.putInt(1);

        context.pendingTransferToES.add(buffer);

        when(_esAdapterMock.writeSignalData(buffer)).thenReturn(new Result(true));

        _procedure.runOnce(1);

        verify(_esAdapterMock).writeSignalData(buffer);

        assertEquals(0, _procedure.getDroppedProcessModelFrames());
        assertEquals(0, _procedure.getDataWriteFailureCount());
    }

    @Test
    public void expect_the_latest_data_to_be_written_when_multiple_data_is_pending() throws Exception {

        _doFirstRunOnceCallToConnect();
        
        final ByteBuffer buf0 = ByteBuffer.allocate(BUF_SIZE);
        final ByteBuffer buf1 = ByteBuffer.allocate(BUF_SIZE);        
        buf1.putShort((short)1);
        context.pendingTransferToES.add(buf0);
        context.pendingTransferToES.add(buf0);
        context.pendingTransferToES.add(buf1);

        when(_esAdapterMock.writeSignalData(buf1)).thenReturn(new Result(true));

        _procedure.runOnce(1);

        verify(_esAdapterMock, times(1)).writeSignalData((ByteBuffer) any());

        assertEquals(2, _procedure.getDroppedProcessModelFrames());
        // Even though only a single write is done, all pending writes shall be cleared
        assertEquals(0, _procedure.getDataWriteFailureCount());
    }

    @Test
    public void expect_disconnect_from_ES_when_an_IOException_is_thrown_during_data_write() throws Exception {

        _doFirstRunOnceCallToConnect();

        final ByteBuffer buf0 = ByteBuffer.allocate(BUF_SIZE);
        final ByteBuffer buf1 = ByteBuffer.allocate(BUF_SIZE);
        context.pendingTransferToES.add(buf0);
        context.pendingTransferToES.add(buf1);

        when(_esAdapterMock.writeSignalData(buf1)).thenThrow(new IOException());

        _procedure.runOnce(1);

        verify(_esAdapterMock).disconnect();
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
        assertEquals(0, context.pendingTransferToES.size());
    }

    @Test
    public void expect_data_write_failure_count_to_be_incremented_if_failure_when_writing_data() throws Exception {

        _doFirstRunOnceCallToConnect();
        final ByteBuffer buf0 = ByteBuffer.allocate(BUF_SIZE);
        final ByteBuffer buf1 = ByteBuffer.allocate(BUF_SIZE);
        context.pendingTransferToES.add(buf0);
        context.pendingTransferToES.add(buf1);

        when(_esAdapterMock.writeSignalData(buf1)).thenReturn(new Result(false));

        _procedure.runOnce(1);

        assertEquals(1, _procedure.getDataWriteFailureCount());
        // The second pending data write shall be cleared as usual
        assertEquals(0, context.pendingTransferToES.size());
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Tests of successful and unsuccessful reading of signal data from the external system.
    // On succes the data shall be added as pending data transfers to the process model
    ////////////////////////////////////////////////////////////////////////////////////////

    @Test
    public void expect_added_pending_transfers_to_PM_when_there_is_data_available() throws Exception {
        _doFirstRunOnceCallToConnect();

        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenReturn(new Result(true));

        _procedure.runOnce(1);

        assertEquals(1, context.pendingTransferToPM.size());
        ByteBuffer values = context.pendingTransferToPM.pollFirst();
        verify(_esAdapterMock).readSignalData(same(values));
        assertEquals(0, _procedure.getDataReadFailureCount());
    }

    @Test
    public void expect_no_added_pending_transfers_to_PM_when_there_is_no_data_available() throws Exception {

        _doFirstRunOnceCallToConnect();

        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenReturn(null);

        _procedure.runOnce(1);

        verify(_esAdapterMock).readSignalData((ByteBuffer)any());
        assertEquals(0, context.pendingTransferToPM.size());
        assertEquals(0, _procedure.getDataReadFailureCount());
    }

    @Test
    public void expect_data_read_failure_count_to_be_incremented_if_failure_when_reading_data() throws Exception {

        _doFirstRunOnceCallToConnect();

        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenReturn(new Result(false));

        _procedure.runOnce(1);

        assertEquals(0, context.pendingTransferToPM.size());
        assertEquals(1, _procedure.getDataReadFailureCount());
    }

    @Test
    public void expect_disconnect_from_ES_when_an_IOException_is_thrown_during_data_read() throws Exception {

        _doFirstRunOnceCallToConnect();

        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenThrow(new IOException());

        _procedure.runOnce(1);

        verify(_esAdapterMock).disconnect();
        assertTrue(_procedure.currentState() instanceof ExternalSystemProcedure.DisconnectedState);
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // timeStepControl calls
    ////////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void expect_timeStepControl_to_be_called_corresponding_to_esFrameCount() throws Exception {

        _doFirstRunOnceCallToConnect();

        when(_esAdapterMock.timeStepControl()).thenReturn(new Result(true));

        _procedure.runOnce(1);
        context.esFrameCount = 1;
        _procedure.runOnce(2);
        context.esFrameCount = 4;
        _procedure.runOnce(3);

        verify(_esAdapterMock, times(4)).timeStepControl();
    }

    @Test
    public void expect_data_read_failure_count_to_be_incremented_when_timeStepControl_indicates_failure() throws Exception {
        _doFirstRunOnceCallToConnect();

        when(_esAdapterMock.timeStepControl()).thenReturn(new Result(true));

        _procedure.runOnce(1);
        assertEquals(0, _procedure.getDataWriteFailureCount());

        context.esFrameCount = 1;
        when(_esAdapterMock.timeStepControl()).thenReturn(new Result(false));
        _procedure.runOnce(2);
        assertEquals(1, _procedure.getDataWriteFailureCount());


        context.esFrameCount = 2;
        when(_esAdapterMock.timeStepControl()).thenReturn(new Result(true));
        _procedure.runOnce(3);
        assertEquals(1, _procedure.getDataWriteFailureCount());
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Capabilities
    ////////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void expect_timeStepControl_NOT_to_be_called_when_capability_isSynchronous_is_set_to_false() throws Exception {

       ESAdapterCapabilities cap = new ESAdapterCapabilities();
        cap.isSynchronous = false;
        _doFirstRunOnceCallToConnect(cap);

        when(_esAdapterMock.timeStepControl()).thenReturn(new Result(true));

        context.esFrameCount = 4;
        _procedure.runOnce(1);

        verify(_esAdapterMock, never()).timeStepControl();
    }

    @Test
    public void expect_es_state_to_be_NA_when_capability_canReportState_is_set_to_false() throws Exception {
       ESAdapterCapabilities cap = new ESAdapterCapabilities();
        cap.canReportState = false;
        _doFirstRunOnceCallToConnect(cap);

        assertEquals(SimState.NOT_AVAILABLE, _procedure.getExternalSystemState());
    }

    @Test
    public void expect_es_state_to_be_NA_when_capability_canReportStatus_is_set_to_false() throws Exception {
        ESAdapterCapabilities cap = new ESAdapterCapabilities();
        cap.canReportStatus = false;
        _doFirstRunOnceCallToConnect(cap);

        assertEquals(SystemStatus.NOT_AVAILABLE, _procedure.getExternalSystemStatus().getStatusCode());
    }

    @Test
    public void do_not_expect_simulator_commands_to_be_executed_when_capability_expectsSimulatorCommands_is_set_to_false() throws Exception {
        ESAdapterCapabilities cap = new ESAdapterCapabilities();
        cap.expectsSimulatorCommands = false;

        _doFirstRunOnceCallToConnect(cap);
        context.pendingSimCommands.add(new Command("FREEZE"));

        when(_esAdapterMock.executeSimCommand((Command)any())).thenReturn(new Result(true));

        _procedure.runOnce(1);

        verify(_esAdapterMock, never()).executeSimCommand((Command)any());
    }

    private void _doFirstRunOnceCallToConnect() throws Exception {
        ESAdapterCapabilities cap = new ESAdapterCapabilities();
        _doFirstRunOnceCallToConnect(cap);
        _procedure.runOnce(0);
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // Control commands
    ////////////////////////////////////////////////////////////////////////////////////////
    @Test
    public void expect_controlCommands_to_be_invoked_on_ESAdapter() throws Exception {

        _doFirstRunOnceCallToConnect(new ESAdapterCapabilities());

        when(_esAdapterMock.timeStepControl()).thenReturn(new Result(true));

        context.esFrameCount = 4;
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(25); } catch (InterruptedException ex) {}
                _procedure.runOnce(1);
            }
        }).start();
                      
        _procedure.invokeControlCommand("cmID", new HashMap<String, String>(){{ put("p1", "v1"); }});

        ArgumentCaptor<Command> argument = ArgumentCaptor.forClass(Command.class);
        verify(_esAdapterMock).invokeControlCommand(argument.capture());
        assertEquals("cmID", argument.getValue().getID());
        assertEquals("v1", argument.getValue().getParameter("p1"));
    }

    private void _doFirstRunOnceCallToConnect(ESAdapterCapabilities cap) throws Exception {
        when(_esAdapterMock.connect()).thenReturn(true);
        when(_esAdapterMock.getStatus()).thenReturn(new SystemStatus(SystemStatus.OK, "its OK"));
        when(_esAdapterMock.getState()).thenReturn(SimState.FREEZE);
        when(_esAdapterMock.readSignalData((ByteBuffer)any())).thenReturn(null);
        when(_esAdapterMock.getCapabilities()).thenReturn(cap);
        when(_esAdapterMock.connect()).thenReturn(true);
        _procedure.runOnce(0);
    }
}
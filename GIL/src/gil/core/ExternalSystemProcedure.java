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
package gil.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.log4j.Logger;
import gil.common.AsyncResult;
import gil.io.IExternalSystemAdapter;
import gil.common.GILConfiguration;
import gil.common.IInvokeable;
import gil.common.Invoker;
import gil.common.Result;
import gil.common.Timeout;

/**
 * This is the logic separated from the controlling thread to handle the communication with the external system.
 * The method runOnce will continously be called from a controlling thread.
 *
 * Methods in this object are not thread safe unless otherwise noted.
 *
 * @author Göran Larsson @ LearningWell AB
 */
public class ExternalSystemProcedure  implements IProcedure {

    private static Logger _logger = Logger.getLogger(ExternalSystemProcedure.class);

    private long _frameCount = - 1;    
    private ByteBuffer _valuesBuf = null;    
    private IState _currentState = null;

    private final int _valuesBufSize;
    private final IntegrationContext _context;
    private final IExternalSystemAdapter _esAdapter;
    private final Timeout _readTimeout;
    private final GILConfiguration _config;
    Invoker _controlCommandInvoker = new Invoker();
    
    private volatile boolean _reconnect = false;
    private volatile int _commandExecutionFailureCount;
    private volatile int _droppedProcessModelFrames;
    private volatile int _dataWriteFailureCount;
    private volatile int _dataReadFailureCount;

    /**
     * @param esAdapter The external system boundary class.
     * @param context The context data to be shared between the {@link ExternalSystemProcedure} and the
     * {@link ProcessModelProcedure}.
     * @param valuesBufSize The number of bytes that must be allocated for signal values read from the external system.
     * @param config Object containing configuration parameters that may be used by this object.
     */
    public ExternalSystemProcedure(IExternalSystemAdapter esAdapter, IntegrationContext context, int valuesBufSize, GILConfiguration config) {
        _esAdapter = esAdapter;
        _context = context;
        _config = config;
        _frameCount = _context.esFrameCount;
        _valuesBufSize = valuesBufSize;
        _readTimeout = new Timeout(config.getESAdapterReadPollRate());
        _currentState = new DisconnectedState();
    }

    public void runOnce(long currentTimeInMilliseconds) {
        _currentState = _currentState.handle(currentTimeInMilliseconds);
        _controlCommandInvoker.executeNextCommandInQueue();
    }

    /**
     * This method is thread safe
     */
    public int getCommandWriteFailureCount() {
        return _commandExecutionFailureCount;
    }

    /**
     * This method is thread safe
     */
    public int getDroppedProcessModelFrames() {
        return _droppedProcessModelFrames;
    }

    /**
     * This method is thread safe
     */
    public int getDataWriteFailureCount() {
        return _dataWriteFailureCount;
    }

    /**
     * This method is thread safe
     */
    public int getDataReadFailureCount() {
        return _dataReadFailureCount;
    }

    public int getExternalSystemState() {
        if (!_esAdapter.getCapabilities().canReportState)
            return SimState.NOT_AVAILABLE;
        if (_currentState instanceof ConnectedState)
            return _esAdapter.getState();
        return SimState.UNKNOWN;
    }

    public SystemStatus getExternalSystemStatus() {
        if (!_esAdapter.getCapabilities().canReportStatus)
            return new SystemStatus(SystemStatus.NOT_AVAILABLE, "The adapter does not support status reporting.");
        if (_currentState instanceof ConnectedState)
            return _esAdapter.getStatus();
        if (_currentState instanceof ErrorState)
            return new SystemStatus(SystemStatus.NOK, ((ErrorState)_currentState).getCause());

        return new SystemStatus(SystemStatus.UNKNOWN, "Not connected");
    }

    public IState currentState() {        
        return _currentState;
    }

    public void reconnect() {
        _reconnect = true;
    }
    
    public Map<String, String> invokeControlCommand(final String commandID, final Map<String, String> parameters)
            throws InterruptedException, ExecutionException {
        if (commandID.equals("reconnect")) {
            reconnect();
        }
        else {
            AsyncResult result = _controlCommandInvoker.schedule(new IInvokeable() {
                public Object invoke() throws Exception {
                    Command cmd = new Command(commandID, parameters);
                    try {
                        return _esAdapter.invokeControlCommand(cmd);
                    } catch(Exception ex) {
                        _logger.error("Error when invoking control command '" + cmd.toString() + "'.", ex);
                        throw ex;
                    }
                }
            });
            return (Map<String, String>) result.get(); // Block until async operation completes
        }
        return new HashMap<String, String>();
    }

    public class DisconnectedState implements IState {
        public IState handle(long currentTimeInMilliseconds)  {
            try {
                _logger.debug("Connecting");
                if (!_esAdapter.connect()) {
                    return this;
                }
                synchronized(_context) {
                    _context.pendingSimCommands.clear();
                    _context.pendingTransferToES.clear();
                }
                _reconnect = false;
                return new ConnectedState();
            } catch(IOException ex) {
                _logger.error("Failure when connecting: " + ex.getMessage());
                _logger.debug(ex.getMessage(), ex);
                return new ErrorState(ex.getMessage());
            }
        }
    }

    public class ConnectedState implements IState {
        public IState handle(long currentTimeInMilliseconds)  {
            try {
                if (_esAdapter.getCapabilities().isSynchronous) {
                    long newFrameCount = _context.esFrameCount;
                    for (long i = _frameCount; i < newFrameCount; i++) {
                        Result result = _esAdapter.timeStepControl();
                        if (!result.isSuccess()) {
                            _logger.warn("Unsuccessful call to timeStepControl. May cause the external system to lag");
                            ++_dataWriteFailureCount;
                        }
                    }
                    _frameCount = newFrameCount;
                }

                if (_valuesBuf == null) {
                     // Must allocate direct since the buffer may be used across boundaries to native code (JNI).
                    _valuesBuf = ByteBuffer.allocateDirect(_valuesBufSize);
                    // Set the order the PM-Adapter expects in the supplied ByteBuffer.
                    _valuesBuf.order(_config.getPMAdapterByteOrder());
                }

                if (_readTimeout.isTimeout(currentTimeInMilliseconds)) {
                    _readTimeout.reset(currentTimeInMilliseconds);
                    Result result = _esAdapter.readSignalData(_valuesBuf);
                    if (result != null) {
                        if (result.isSuccess()) {
                            synchronized(_context) {
                                _valuesBuf.rewind();
                                _context.pendingTransferToPM.add(_valuesBuf);
                                _valuesBuf = null;
                            }
                        }
                        else {
                            _logger.warn("Failed to read: " + result.getErrorDescription());
                            ++_dataReadFailureCount;
                        }
                    }
                }

                Command nextCommand;
                synchronized(_context) {
                    nextCommand = _context.pendingSimCommands.poll();
                }
                if (_esAdapter.getCapabilities().expectsSimulatorCommands) {
                    if (nextCommand != null) {
                        _logger.debug("Executing command: " + nextCommand.getID());
                        Result cmdResult = _esAdapter.executeSimCommand(nextCommand);
                        if (cmdResult.isSuccess()) {
                            _logger.debug("Command executed: " + nextCommand.getID());
                        }
                        else {
                            _logger.warn("Failed to execute command " + nextCommand.toString() + ": " + cmdResult.getErrorDescription());
                            ++_commandExecutionFailureCount;
                        }
                    }
                }
                ByteBuffer values;
                synchronized(_context) {
                    values = _context.pendingTransferToES.pollLast();
                    if (_context.pendingTransferToES.size() > 0) {
                        _logger.warn(String.format("Dropped %d PM frames(s)", _context.pendingTransferToES.size()));
                        _droppedProcessModelFrames += _context.pendingTransferToES.size();
                        _context.pendingTransferToES.clear();
                    }
                }
                if (values != null) {
                    _transferSignalDataES(values);
                }
            } catch(IOException ex) {
                _logger.error("Failure in communication with external system. ", ex);
                _reconnect = true;
            }
            if (_reconnect) {
                _esAdapter.disconnect();                
                return new DisconnectedState();
            }
            return this;
        }

        private void _transferSignalDataES(ByteBuffer values) throws IOException {
            Result result = _esAdapter.writeSignalData(values);
            if (!result.isSuccess()) {
                _logger.warn("Failed to write: " + result.getErrorDescription());
                ++_dataWriteFailureCount;
            }
        }
    }

    public class ErrorState implements IState {
        private String _cause = "";

        public ErrorState(String cause) {
            _cause = cause;
        }

        public IState handle(long currentTimeInMilliseconds)  {
            if (_reconnect) {
                return new DisconnectedState();
            }
            return this;
        }

        public String getCause() {
            return _cause;
        }
    }   
}

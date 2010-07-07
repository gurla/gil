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
package gil.common;

/**
 * Creats a readable string of the content in a ByteBuffer.
 * @author Göran Larsson @ LearningWell AB
 */
public class ByteBufferFormatter {
    byte[] _data;
    public ByteBufferFormatter(byte[] data) {
        _data = data;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Size ");
        sb.append(_data.length);
        sb.append(" bytes Data:");
        int i = 0;
        for (byte b : _data) {
            sb.append(String.format("%02x", b));
            if ((++i % 4) == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }
}

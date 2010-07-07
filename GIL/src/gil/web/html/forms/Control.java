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
package gil.web.html.forms;

import gil.web.html.Tag;

/**
 * Base class for html form controls such as input, select, checkbox, etc. Controls are contained in a 
 * {@link Form Form}.
 * 
 * @author Göran Larsson @ LearningWell AB
 */
public class Control extends Tag {
    public enum ControlType {
            input,
            label,
            select
    }

    public Control(ControlType controlType, String id) {
        super(controlType.name(), id);
    }

    public Control(ControlType controlType) {
        super(controlType.name());
    }
}


/**
 * This file is part of Todo.txt Touch, an Android app for managing your todo.txt file (http://todotxt.com).
 *
 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)
 *
 * LICENSE:
 *
 * Todo.txt Touch is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.
 *
 * Todo.txt Touch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with Todo.txt Touch.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * @author Todo.txt contributors <todotxt@yahoogroups.com>
 * @license http://www.gnu.org/licenses/gpl.html
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 */
package nl.mpcjanssen.todotxtholo.task;

import com.dropbox.sync.android.DbxAccountManager;
import com.dropbox.sync.android.DbxFileSystem;
import nl.mpcjanssen.todotxtholo.TodoTxtTouch;

import java.util.*;


/**
 * Implementation of the TaskBag
 *
 * @author Tim Barlotta, Mark Janssen
 *         <p/>
 *         The taskbag is the backing store for the task list used by the application
 *         It is loaded from and stored to the local copy of the todo.txt file and
 *         it is global to the application so all activities operate on the same copy
 */
public class TaskBag  {
    final static String TAG = TodoTxtTouch.class.getSimpleName();

    private ArrayList<Task> tasks = new ArrayList<Task>();

	private DbxAccountManager dbxAcctMgr;
	private DbxFileSystem dbxFs;


	private ArrayList<TodoTxtTouch> obs = new ArrayList<TodoTxtTouch>();

    public void init (String todoContents) {
        tasks = new ArrayList<Task>();
        long lineNr = 0;
        for (String line : todoContents.split("\n")) {
            lineNr++;
            line = line.trim();
            if (!line.equals("")) {
                Task t = new Task(lineNr, line);
                tasks.add(t);
            }
        }
    }

    public int size() {
        return tasks.size();
    }

    public ArrayList<Task> getTasks() {
        return tasks;
    }

    public Task getTaskAt(int position) {
        return tasks.get(position);
    }

    public void addAsTask(String input) {
        try {
            Task task = new Task(tasks.size(), input, new Date());
            tasks.add(task);
        } catch (Exception e) {
            throw new TaskPersistException("An error occurred while adding {"
                    + input + "}", e);
        }
    }

    public void updateTask(Task task, String input) {
        Task toUpdate = find(task);
        toUpdate.init(input, null);
    }

    public Task find(Task task) {
        Task found = TaskBag.find(tasks, task);
        return found;
    }

    public Task find(String text) {
        Task found = TaskBag.find(tasks, text);
        return found;
    }


    public void deleteTasks(List<Task> toDelete) {
		for (Task task : toDelete) {
			Task found = TaskBag.find(tasks, task);
			if (found != null) {
				tasks.remove(found);
			} else {
				throw new TaskPersistException("Task not found, not deleted");
			}
		}
	}

    public String getTodoContents() {
        String output = "";
        for (Task t : tasks) {
            output = output + t.inFileFormat() + "\n";
        }
        return output;
    }

    public void reload (String contents) {

    }

    public ArrayList<Priority> getPriorities() {
        // TODO cache this after reloads?
        Set<Priority> res = new HashSet<Priority>();
        for (Task item : tasks) {
            res.add(item.getPriority());
        }
        ArrayList<Priority> ret = new ArrayList<Priority>(res);
        Collections.sort(ret);
        return ret;
    }

    public ArrayList<String> getContexts() {
        // TODO cache this after reloads?
        Set<String> res = new HashSet<String>();
        for (Task item : tasks) {
            res.addAll(item.getContexts());
        }
        ArrayList<String> ret = new ArrayList<String>(res);
        Collections.sort(ret);
        ret.add(0, "-");
        return ret;
    }

    public ArrayList<String> getProjects() {
        // TODO cache this after reloads?
        Set<String> res = new HashSet<String>();
        for (Task item : tasks) {
            res.addAll(item.getProjects());
        }
        ArrayList<String> ret = new ArrayList<String>(res);
        Collections.sort(ret);
        ret.add(0, "-");
        return ret;
    }

    private static Task find(List<Task> tasks, Task task) {
        for (Task task2 : tasks) {
            if (task2 == task || (task2.getText().equals(task.getOriginalText())
                    && task2.getPriority() == task.getOriginalPriority())) {
                return task2;
            }
        }
        return null;
    }

    private static Task find(List<Task> tasks, String text) {
        for (Task task2 : tasks) {
            if (task2.inFileFormat().equals(text)) {
                return task2;
            }
        }
        return null;
    }

    public void init(ArrayList<Task> newTasks) {
        tasks.clear();
        tasks.addAll(newTasks);
    }
}

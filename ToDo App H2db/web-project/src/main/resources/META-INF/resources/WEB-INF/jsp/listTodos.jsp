<%@ include file="common/header.jspf"%>
<%@ include file="common/navigation.jspf"%>
<h1>Hello ${name}! We extend you a warm welcome!</h1>
<hr>
<h2>Your Todos</h2>

<table class="table table-bordered table-striped">
    <thead class="table-dark">
        <tr>

            <th>Description</th>
            <th>Target Date</th>
            <th>Done?</th>
            <th></th>
               <th></th>
        </tr>
    </thead>
    <tbody>
        <c:forEach items="${todos}" var="todo">
            <tr>
                <td>${todo.description}</td>
                <td>${todo.targetDate}</td>
                <td>${todo.done}</td>
                <td><a href="delete-todo?id=${todo.id}" class="btn btn-warning">DELETE</a></td>
                <td><a href="update-todo?id=${todo.id}" class="btn btn-success">UPDATE</a></td>
            </tr>
        </c:forEach>
    </tbody>
</table>

<a href="add-todo" class="btn btn-success">Add Todo</a>

<%@ include file="common/footer.jspf"%>